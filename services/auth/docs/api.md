# API reference

> [auth](../README.md) › API

The complete auth API. Everything in **§1–2 is live and pinned by contract
tests**; **§3 is the approved design** for Phases C/D/E — built in that
order, each endpoint arriving with its tests. Interactive docs: Swagger UI
at `/docs` (springdoc; spec at `/v3/api-docs`).

## Contents

- [Conventions](#conventions)
- [§1 Public infrastructure](#1-public-infrastructure)
- [§2 Live endpoints](#2-live-endpoints)
- [§3 Designed, not yet implemented](#3-designed-not-yet-implemented)
- [Error envelope](#error-envelope)

---

## Conventions

- Base path `/api/v1` (URL versioning — breaking changes get a `v2`).
- Auth input: `Authorization: Bearer <jwt>` (gateway style) **or** the
  `om_access` cookie (direct dev calls) — `TokenCookieService` accepts both,
  header wins. Refresh/logout additionally use the `om_refresh` cookie.
- Identity is **always** the token's `sub` — never a body/path parameter.
- Success = the resource; errors = the [envelope](#error-envelope).
- 200 read / 201 created / 202 accepted / 204 no content — the frontend keys
  off these.

## §1 Public infrastructure

| Method & path | Notes |
|---|---|
| `GET /` | Service info card |
| `GET /health/live` · `GET /health/ready` | Probes (ready checks Postgres) |
| `GET /.well-known/jwks.json` | Public RSA keys, `Cache-Control: max-age=900`, no private material |

## §2 Live endpoints

### auth — password sessions

| Method & path | Auth | Success | Errors |
|---|---|---|---|
| `POST /api/v1/auth/register` | — | **201** `{email, password, name, username?}` → user JSON + both cookies set | 400 `validation_failed`/`malformed_json` · 409 `email_taken`, `username_taken` |
| `POST /api/v1/auth/login` | — | **200** `{email, password}` → user JSON + cookies | 401 `invalid_credentials` (identical for unknown email — no enumeration) |
| `POST /api/v1/auth/refresh` | refresh cookie | **200** — rotation: presented token consumed, successor in same family, new cookies | 401 `missing_refresh_token`, `invalid_refresh_token`, `refresh_token_expired`, `refresh_token_reused` (family revoked), `account_deleted` |
| `POST /api/v1/auth/logout` | refresh cookie (access token **not** required) | **204** always — best-effort revoke + cookies cleared | — |

### auth — session management (devices)

| Method & path | Auth | Success | Errors |
|---|---|---|---|
| `GET /api/v1/auth/sessions` | access | **200** — live session families, newest first: `[{familyId, userAgent, ipAddress, createdAt, expiresAt, current}]` (`current` = family of the presented `om_refresh`) | 401 `unauthorized` |
| `DELETE /api/v1/auth/sessions/{familyId}` | access | **204** — revoke one device session (ownership-guarded) | 404 `session_not_found` |
| `POST /api/v1/auth/sessions/revoke-all` | access | **204** — "log out everywhere", incl. the calling device | 401 |

### users

| Method & path | Auth | Success | Errors |
|---|---|---|---|
| `GET /api/v1/users/me` | access | **200** — identity + `loginMethods` + `profile` (see below) | 401 `unauthorized` · 404 `user_not_found`, `profile_not_found` |
| `PATCH /api/v1/users/me` | access | **200** — partial; null fields untouched; map fields replace whole object | 400 `validation_failed` · 409 `username_taken` |
| `DELETE /api/v1/users/me` | access | **204** — soft delete + all sessions revoked | 401 · 404 |

`GET /users/me` response shape:

```json
{
  "id": "1d3e2dfd-…", "email": "me@x.dev", "name": "Bomo",
  "avatarUrl": null, "emailVerified": false, "roles": ["user"],
  "loginMethods": {"password": true, "providers": ["discord"]},
  "profile": {
    "username": "bomo", "bio": null,
    "socialLinks": {"discord": "bomo"},
    "accentColor": "#34d399", "language": "en",
    "notificationPreferences": {}, "avatarUrl": null
  }
}
```

### auth — login methods: password credentials (Phase C, live)

| Method & path | Auth | Success | Errors |
|---|---|---|---|
| `POST /api/v1/auth/credentials` | access | **201** `{password}` — add password to an OAuth-only account | 400 `validation_failed` · 409 `password_exists` |
| `PATCH /api/v1/auth/credentials` | access | **204** `{currentPassword, newPassword}` — change password, **revokes every other device session** (the calling device survives via its refresh cookie) | 401 `invalid_credentials` · 404 `password_not_set` |
| `DELETE /api/v1/auth/credentials` | access | **204** `{currentPassword}` — remove password | 401 · 404 `password_not_set` · 409 `last_login_method` |

### auth — Discord OAuth (Phase C, live)

Browser flows — **302 redirects, not JSON**. The `state` CSRF binding
(query param ↔ signed `om_oauth` httpOnly cookie) is validated on every
callback. Full flow description: [accounts.md](accounts.md).

| Method & path | Auth | Success | Errors (redirect with `?error=`) |
|---|---|---|---|
| `GET /api/v1/auth/discord` | — | **302** → Discord authorize URL (+ `om_oauth` cookie) | — |
| `GET /api/v1/auth/discord/link` | access | **302** → Discord (state binds the user id) | 401 JSON if anonymous |
| `GET /api/v1/auth/discord/callback` | state cookie | login / auto-link by verified email / create identity / attach to current user → **302** to success page (+ auth cookies on login/signup) | `oauth_state_mismatch`, `provider_already_linked`, `oauth_email_required`, `oauth_failed` |
| `DELETE /api/v1/auth/connections/discord` | access | **204** — unlink | 404 `provider_not_linked` · 409 `last_login_method` |

## §3 Admin & moderation (Phase E, live)

Authorization: `@PreAuthorize` against the JWT `roles` claim with the
hierarchy **owner ⊃ admin ⊃ moderator** (an admin passes moderator checks).
403 `forbidden` when the roles don't qualify. **The first registered account
on an empty platform becomes `owner`** (bootstrap).

| Method & path | Role | Notes |
|---|---|---|
| `GET /api/v1/admin/users?query=&page=&size=` | moderator | paged `{items, page, size, total}`, query matches email/name |
| `GET /api/v1/admin/users/{id}` | moderator | detail: identity, roles, bans, warnings |
| `POST /api/v1/admin/users/{id}/ban` | admin | **201** `{reason, expiresAt?}` — revokes all sessions; login/refresh refuse with `403 account_banned`; emits `user.banned` (outbox) |
| `POST /api/v1/admin/users/{id}/unban` | admin | **204** — lifts the active ban |
| `POST /api/v1/admin/users/{id}/warn` | moderator | **201** `{reason}` |
| `PATCH /api/v1/admin/users/{id}/roles` | admin | **200** `{roles:[...]}` — replaces wholesale, **future tokens only** (re-login to pick up) |
| `GET /api/v1/admin/users/{id}/export` | admin | **200** — GDPR export of the auth slice |
| `POST /api/v1/admin/users/{id}/erase` | owner | **202** — anonymize identity/profile + revoke all sessions + `user.deleted` event |

## Error envelope

Every error — validation, auth, Spring Security — uses:

```json
{"code": "email_taken", "message": "An account with this email already exists", "field": "email"}
```

| Code | HTTP | Status |
|---|---|---|
| `validation_failed` | 400 | live |
| `malformed_json` | 400 | live |
| `invalid_credentials` | 401 | live |
| `unauthorized` | 401 | live |
| `missing_refresh_token` | 401 | live |
| `invalid_refresh_token` | 401 | live |
| `refresh_token_expired` | 401 | live |
| `refresh_token_reused` | 401 | live — replay detected, family revoked |
| `account_deleted` | 401 | live |
| `oauth_email_required` | — | live (OAuth redirect `?error=`) |
| `oauth_state_mismatch` / `oauth_failed` | — | live (OAuth redirect `?error=`) |
| `email_taken` / `username_taken` | 409 | live |
| `user_not_found` / `profile_not_found` | 404 | live |
| `session_not_found` | 404 | live |
| `password_exists` / `password_not_set` / `last_login_method` / `provider_not_linked` / `provider_already_linked` | — | live |
| `forbidden` | 403 | live |
| `internal_error` | 500 | live |
| `email_already_verified` | 409 | live |
| `invalid_token` / `token_expired` | 400 | live — e-mailed confirm tokens |
| `rate_limited` (+ `Retry-After` header) | 429 | live — email-sending endpoints (in-memory fixed window) |
| `account_banned` | 403 | live — enforced at login + refresh |
| `already_banned` / `ban_not_found` / `unknown_role` | — | live (admin surface) |

---

Related: [testing.md](testing.md) · [tokens.md](tokens.md) ·
[accounts.md](accounts.md)
