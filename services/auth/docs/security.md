# Security notes

> [auth](../README.md) › Security

The defensive posture: what protects what, and the known gaps.

## Contents

- [Password handling](#password-handling)
- [Cookie posture](#cookie-posture)
- [CSRF stance](#csrf-stance)
- [Recovery & revocation story](#recovery--revocation-story)
- [Known gaps / future work](#known-gaps--future-work)

---

## Password handling

- **BCrypt strength 12** (`spring-security-crypto`). Greenfield users, so no
  back-compat with better-auth's scrypt — if we ever import v1 users,
  Spring's `SCryptPasswordEncoder` can verify old hashes and we lazily
  rehash on next login.
- **No user enumeration:** failed login returns the identical
  `invalid_credentials` envelope for unknown email, deleted user, and wrong
  password — and unknown accounts run a bcrypt compare against a **dummy
  hash** so response timing doesn't leak which emails exist.
- **Password policy:** 8–128 chars **and** ≤72 UTF-8 bytes (`@Size` +
  `@PasswordBytes` bean validation). The byte cap sits exactly at bcrypt's
  input boundary — bcrypt only hashes the first 72 bytes, so longer secrets
  would verify differently than the user set them. No composition rules
  (required symbols, entropy scoring, breach lists) by design — length plus
  the byte boundary is the whole policy.

## Cookie posture

| Cookie | Flags |
|---|---|
| `om_access` | `httpOnly`, `SameSite=Lax`, `Secure` (when `AUTH_COOKIE_SECURE=true`), `path=/` (gateway reads it anywhere), `max-age=15m` |
| `om_refresh` | `httpOnly`, `SameSite=Lax`, `Secure`, `path=/api/v1/auth` (only auth endpoints ever need it), `max-age=7d` |

- JS can't read either cookie → XSS can't exfiltrate long-lived credentials.
- `Lax` blocks cross-site POSTs from carrying them → strongest CSRF
  mitigation available without breaking the gateway flow.
- The 15-min access cookie is deliberately short: a stolen one expires
  fast, and the thief can't refresh without stealing `om_refresh` too —
  which triggers [family revocation](tokens.md#refresh-rotation--theft-detection)
  on first rotation conflict.

### Why not `__Host-om_refresh`?

The `__Host-` prefix is the strongest cookie binding browsers offer: it
forces `Secure`, `Path=/`, and **no `Domain`**. The blocker is `Path=/` —
`om_refresh` is deliberately path-scoped to `/api/v1/auth` so only auth
endpoints ever receive it. Adopting the prefix would mean either widening
the refresh cookie to every path (exposed on every gateway request) or
keeping the scoping and dropping the prefix. **Decision:** keep the path
scoping, skip the prefix; revisit only if the gateway stops forwarding
path-scoped cookies or the auth surface moves to root paths. `om_access`
already needs `path=/` (the gateway reads it anywhere) and could take the
prefix — but it's the short-lived cookie, so the marginal win is small.

## CSRF stance

CSRF protection is **disabled** on this service, deliberately:

- The browser never talks to auth directly — the Go gateway is the only
  public surface and forwards `Authorization` headers (immune to CSRF).
- Direct cookie auth exists for dev convenience; `SameSite=Lax` already
  neutralizes the classic cross-site form POST for it.
- Revisit when the gateway ships its own cookie-handling: it will need a
  CSRF story for the cookie-carrying mutations it terminates.

## Recovery & revocation story

| Event | Mechanic | Blast radius |
|---|---|---|
| Logout | `revoke(rawToken)` — one token dies; **best-effort** (no access token needed, cookies always cleared) | one device |
| Revoke one device (`DELETE /auth/sessions/{familyId}`) | ownership-guarded family revoke | one device |
| "Log out everywhere" (`POST /auth/sessions/revoke-all` / account deletion) | `revokeAllForUser` | every session |
| Stolen refresh cookie replayed | reuse detection → **whole family revoked**; consume is **atomic** (concurrent double-refresh can't mint two successors) and every family has an **absolute 7-day window** from creation | one login chain (the thief's and the victim's) |
| Signing key rotated | old tokens fail signature check | everyone, ≤15 min to re-login |
| Banned user (Phase E) | `user.banned` → Kafka → gateway Redis blocklist | banned user, ≤15 min lag |
| Forgot password (Phase D) | e-mailed token: hashed, **single-use** (consumed atomically), re-requesting **supersedes** the old one | — |

The sessions list (`GET /auth/sessions`) exposes device metadata
(user agent, IP) to the authenticated user **only for their own sessions** —
ownership is enforced server-side by the token's `sub`.

## Rate limiting & role ladder

- `429 rate_limited` + `Retry-After` on email-sending endpoints
  (verify-resend: 3/h per user, email-change: 5/h per user, forgot: 5/h per
  email+IP) — in-memory fixed window; resets on restart, per instance. The
  limiter is **swept and size-capped**, so fake identities can't balloon
  its memory.
- **Login throttling:** ≥10 failed attempts per account in 15 min → every
  further attempt gets the uniform `invalid_credentials` 401 for the
  window — deliberately indistinguishable from a wrong password, so the
  throttle itself doesn't enable enumeration.
- **Client-IP trust:** `X-Forwarded-For` is honoured only when the direct
  TCP peer is listed in `AUTH_TRUSTED_PROXY_IP` (default: empty = trust
  nobody) — the header can't be used to spoof rate-limit identities or
  session metadata when auth is exposed directly.
- Role ladder **owner ⊃ admin ⊃ moderator** via a Spring `RoleHierarchy`
  over the JWT `roles` claim; the first registered account bootstraps as
  `owner`. Authorities are upper-cased (`ROLE_ADMIN`) — hasRole() compares
  case-sensitively.
- Bans: active ban (not lifted, not expired) blocks login AND refresh with
  `403 account_banned`; banning revokes every live session. The residual
  ≤15-min access-token window is closed fleet-wide by the gateway's Redis
  blocklist fed by the `user.banned` outbox event (wiring pending).

## Known gaps / future work

Recently closed (hardening waves 1–2):

- ✅ **Login throttling** — per-account 10-failures/15-min window, uniform
  `invalid_credentials` response (no enumeration through the throttle).
- ✅ **Rate-limiter memory** — bounded via sweep + entry cap.
- ✅ **Refresh consume** — atomic rotation, absolute 7-day family window.
- ✅ **Admin actions audited** — `audit_log` table (V4 migration).
- ✅ **Provider tokens not persisted** — OAuth token columns dropped (V5);
  only stable provider ids remain.
- ✅ **Verification tokens superseded** — a fresh request invalidates the
  previous token.
- ✅ **Dead token rows swept** — daily cleanup job (30-day retention after
  expiry/revocation, kept for theft forensics) bounds table growth.

Still open:

- **MFA hooks** — roadmapped, nothing built.
- **No sensitive-op re-auth** — `DELETE /users/me` needs only a valid access
  token (≤15 min window); industry practice would demand recent login.
  Deferred as hardening.
- **No password blocklist** — no breach-list/common-password checks; length
  plus the 72-byte boundary is the whole policy, by design.
- **CSRF** — relies on `SameSite=Lax` plus the gateway being the only
  public surface; explicit CSRF/Origin checks at the gateway are deferred.
- **JWKS fail-closed** — that behavior lives in the gateway; auth only
  serves the key set.

---

Related: [tokens.md](tokens.md) · [configuration.md](configuration.md)
