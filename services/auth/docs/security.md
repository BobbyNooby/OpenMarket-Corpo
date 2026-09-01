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
- Password policy: 8–128 chars (`RegisterRequest` bean validation). Policy
  enforcement beyond length (breach lists, entropy) is a later concern.

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
| Stolen refresh cookie replayed | reuse detection → **whole family revoked** | one login chain (the thief's and the victim's) |
| Signing key rotated | old tokens fail signature check | everyone, ≤15 min to re-login |
| Banned user (Phase E) | `user.banned` → Kafka → gateway Redis blocklist | banned user, ≤15 min lag |
| Forgot password (Phase D) | `verification_tokens` single-use hash, `used_at` set on consume | — |

The sessions list (`GET /auth/sessions`) exposes device metadata
(user agent, IP) to the authenticated user **only for their own sessions** —
ownership is enforced server-side by the token's `sub`.

## Rate limiting & role ladder

- `429 rate_limited` + `Retry-After` on email-sending endpoints
  (verify-resend: 3/h per user, email-change: 5/h per user, forgot: 5/h per
  email+IP) — in-memory fixed window; resets on restart, per instance.
- Role ladder **owner ⊃ admin ⊃ moderator** via a Spring `RoleHierarchy`
  over the JWT `roles` claim; the first registered account bootstraps as
  `owner`. Authorities are upper-cased (`ROLE_ADMIN`) — hasRole() compares
  case-sensitively.
- Bans: active ban (not lifted, not expired) blocks login AND refresh with
  `403 account_banned`; banning revokes every live session. The residual
  ≤15-min access-token window is closed fleet-wide by the gateway's Redis
  blocklist fed by the `user.banned` outbox event (wiring pending).

## Known gaps / future work

- **Expired refresh rows accumulate** — needs a scheduled cleanup job.
- **`verifications` rate limiting** — password reset must be rate-limited
  before Phase D ships (`429 rate_limited` is already reserved in the API
  contract).
- **No account lockout / rate limiting yet** (login/register brute force).
- **Sensitive-op re-auth** — `DELETE /users/me` needs only a valid access
  token (≤15 min window); industry practice would demand recent login.
  Deferred as hardening.
- **MFA hooks** — roadmapped, nothing built.
- **Auditable auth events** (login attempts, resets) — Phase E audit log.

---

Related: [tokens.md](tokens.md) · [configuration.md](configuration.md)
