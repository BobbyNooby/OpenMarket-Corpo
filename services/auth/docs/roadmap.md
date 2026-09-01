# Roadmap

> [auth](../README.md) › Roadmap

Build order and what's deliberately deferred.

## Status

- [x] **Phase A — schema**
  Flyway V1 (`users` / `credentials` / `oauth_accounts` / `refresh_tokens` /
  `verification_tokens` / RBAC + seeds) + V2 (`user_bans` / `user_warnings` /
  `outbox_events`, refresh-token device metadata), entities, repos.
- [x] **Phase B — password auth + JWT**
  register / login / refresh / logout, RS256 + JWKS, cookie transport,
  rotation + theft detection, `/users/me` CRUD, session management
  (`GET/DELETE /sessions`, `revoke-all`), error envelope, Swagger.
- [x] **Contract + test suite**
  Full API surface designed and written into [api.md](api.md) (live vs
  designed); 53 contract + unit tests pinning it ([testing.md](testing.md)).
  Two real bugs caught by the suite so far: DELETE /users/me returned 200
  instead of 204, and logout required a (possibly expired) access token.
- [x] **Phase C — Discord OAuth + login-method endpoints**
  Authorization-code grant implemented by hand (RestClient + HMAC-signed
  `om_oauth` state cookie): login/signup/link flows, verified-email
  auto-link, `POST/PATCH/DELETE /auth/credentials`, `DELETE
  /auth/connections/discord`. Discord's HTTP API is stubbed two ways:
  MockWebServer in unit tests, `scripts/fake-discord.py` for the live flow
  test. Real Discord app credentials only matter in production.
- [x] **Phase D — email flows**
  verify-email (signup + email change via one confirm endpoint),
  forgot/reset password (creates a credential for OAuth-only accounts,
  revokes all sessions), dev mail = app log / real SMTP via `MAIL_HOST`
  (Mailpit in compose), `429 rate_limited` + `Retry-After` on
  email-sending endpoints (in-memory fixed window).
- [x] **Phase E — RBAC + moderation surface**
  Admin endpoints with `@PreAuthorize` + **owner ⊃ admin ⊃ moderator**
  hierarchy, first-registered-account-becomes-owner bootstrap, bans
  enforced at login/refresh (`403 account_banned`) + all sessions revoked
  on ban, warnings, role patching (future tokens only), GDPR export/erase,
  `user.banned` / `user.deleted` / `user.unbanned` written to the outbox
  (Kafka relay still deferred — the gateway's Redis blocklist consumes
  these once wired).

## Deliberately deferred

| Item | Why / when it arrives |
|---|---|
| Testcontainers integration tests (transaction semantics, migrations) | partially covered by `scripts/flow-test.sh`; finer-grained cases land with the platform fast-follow |
| Kafka outbox relay + `user.created` / `user.deleted` events | events phase; `outbox_events` table already exists |
| `profile_reviews` / `users_activity` wiring | tables exist (v1 mapping); features come with trust-score / presence work |
| gRPC internal API | gateway→service sync calls migrate from HTTP later |
| Gateway JWT validation + cookie forwarding | separate gateway work; blocks real end-to-end, not auth itself |
| Expired refresh-token cleanup job | see [security.md](security.md#known-gaps--future-work) |
| Login attempt rate limiting / lockout | gateway-side limiting first; `rate_limited` machinery already in place |

---

Related: [accounts.md](accounts.md) · repo-wide plan in
[`docs/ARCHITECTURE.md`](../../../docs/ARCHITECTURE.md)
