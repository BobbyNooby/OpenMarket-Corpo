# Tokens

> [auth](../README.md) › Tokens

The two-token design, what's inside the JWT, and how rotation detects
cookie theft.

## Contents

- [Token strategy](#token-strategy)
- [JWT claims](#jwt-claims)
- [Refresh rotation & theft detection](#refresh-rotation--theft-detection)
- [Why not the v1 (better-auth) way](#why-not-the-v1-better-auth-way)

---

## Token strategy

| Token | Type | Transport | Lifetime | Storage |
|---|---|---|---|---|
| Access | RS256 JWT | `om_access` httpOnly cookie (or `Authorization` header) | 15 min (`jwt.access-ttl-minutes`) | nowhere — stateless |
| Refresh | opaque 256-bit random | `om_refresh` httpOnly cookie (`path=/api/v1/auth`) | 7 days per family, absolute (`jwt.refresh-ttl-days`) | **SHA-256 hash only**, in `auth.refresh_tokens` |

Why this split:

- The access token is *proof*, not state — no DB hit to verify, expires fast,
  safe to hand to any service.
- The refresh token is the only thing that can mint new access tokens, so it
  is long-lived but **never stored in readable form** and **rotated on every
  use**. Logout and theft response are possible precisely because the hashes
  live in Postgres (decision: Postgres over Redis — durable, auditable,
  transactional with bans).

## JWT claims

```json
{
  "sub": "1d3e2dfd-…",        // user id (uuid)
  "roles": ["user"],           // role ids for gateway/service checks
  "iss": "auth",
  "aud": "openmarket",
  "iat": 1788266215,
  "exp": 1788267115,           // now + 15 min
  "jti": "d85d5f11-…"          // unique token id
}
```

Header carries `kid` matching the JWKS entry — ready for multi-key rotation.

Email, profile, etc. are deliberately **not** claims: stale claims are a bug
factory. Services that need more ask auth or consume Kafka events.

Verification (both here and at the gateway): signature, expiry, `iss`, `aud`
(`JwtKeyConfig#jwtDecoder`). The `roles` claim is converted to Spring
authorities (`ROLE_user`, …) so `hasRole("admin")` works today.

## Refresh rotation & theft detection

Every login starts a **family** (`family_id`, a uuid). Rules:

1. `refresh` consumes the presented token (`revoked_at = now`) and issues
   the successor **in the same family**. Only the newest member works.
2. The consume is an **atomic conditional update** —
   `UPDATE … SET revoked_at = now WHERE id = ? AND revoked_at IS NULL` — and
   the code branches on the row count. Of two concurrent refreshes exactly
   one wins; the loser sees the token as already consumed and takes rule 3.
   UX consequence: two tabs racing a refresh may log the user out
   everywhere.
3. Presenting an **already-consumed** token means someone is replaying a
   stolen cookie → the **entire family is revoked** and the client must log
   in again.
4. The successor **inherits the family's original expiry** — a family dies
   7 days after login no matter how often it rotated (absolute window, not a
   sliding one). Only a fresh login buys a fresh 7 days.
5. `logout` revokes just the presented token (also a conditional update),
   and "log out everywhere" is a single bulk `UPDATE` — so revocation beats
   a concurrent rotation in both orderings.

Implementation notes (two bugs the smoke test caught — both worth knowing):

- **The revocation must survive the 401.** Reuse response = revoke family +
  throw, all inside the rotate transaction. A naive version rolls the
  revocation back with the exception. Fix: the family revoke runs in an
  independent-commit `TransactionTemplate` (`PROPAGATION_REQUIRES_NEW`).
  (Note: `@Transactional(REQUIRES_NEW)` on a *self-invoked* method does
  nothing — the call bypasses the Spring proxy.)
- **The successor token is the handout.** If the auth flow called
  `issue()` again after `rotate()`, every refresh would silently start a
  *new* family and theft detection could never see the real session.
  `rotate()` returns `(entity, rawToken)` and that raw token is exactly what
  goes into the cookie.

And one the smoke test *couldn't* catch, because it's a race, not a
sequence:

- **Consume must be atomic.** The first version read `revoked_at` and then
  wrote it (check-then-act): two tabs refreshing at the same instant could
  both pass the check and fork the family, quietly disarming theft
  detection. Consumption is now the conditional `UPDATE` from rule 2, and
  the same pattern guards single-token `logout` and the revoke-all bulk
  update. (Bulk `@Modifying` updates bypass the JPA persistence context, so
  after a successful consume the service mirrors `revoked_at`/`used_at`
  onto the loaded entity — flush then rewrites the identical value instead
  of ever resurrecting the row with a stale null.)

Families double as **device sessions**: each token stores `user_agent` +
`ip_address`, and `GET /api/v1/auth/sessions` lists live families (the
"manage devices" UI), with per-family revoke and revoke-all.

Verified sequence: login → refresh → refresh → replay old token →
`401 refresh_token_reused` → newest token now dead too (`401`).

## Why not the v1 (better-auth) way

v1 kept a `session` row per login (opaque cookie → DB lookup on every
request). That's the classic tradeoff: instant revocation, but auth is on
the hot path and the session table grows forever. v2 flips it: stateless
access tokens, DB only for the refresh chain, revocation semantics preserved
by rotation families. The gateway-side Redis blocklist (Phase E) closes the
last gap (banned user keeps a valid access token for ≤15 min).

---

Related: [api.md](api.md) · [security.md](security.md) ·
[keys](configuration.md#key-management-rs256)
