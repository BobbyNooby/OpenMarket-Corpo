# Accounts & linking

> [auth](../README.md) › Accounts & linking

Identity vs login methods — how email↔Discord linking works (implemented, Phase C).

## Contents

- [The model](#the-model)
- [All the flows](#all-the-flows)
- [The OAuth round-trip](#the-oauth-round-trip)
- [Guard rails](#guard-rails)
- [Versus better-auth's single `account` table](#versus-better-auths-single-account-table)

---

## The model

Login methods are separated from identity. Email belongs to the **person**,
never to a login method:

```
auth.users                 ← THE identity (one row per human): id, email, name
   ├── auth.credentials    ← 0..1 row per user: "can ALSO log in with password"
   │                          (PK = user_id → at most one password, enforced by DB)
   └── auth.oauth_accounts ← 0..N rows per user: "can ALSO log in with Discord"
                              UNIQUE (provider, provider_account_id)
```

Consequences:

- A Discord-first user has `oauth_accounts` rows and no `credentials` row —
  the `users.email` still exists (from Discord, if verified).
- Adding a password later = inserting one `credentials` row.
- "Change password" = update `credentials.password_hash` (never touches
  identity).
- Provider tokens (Discord access/refresh) live on the `oauth_accounts` row,
  never on the user.

## All the flows

| Scenario | What happens |
|---|---|
| **Discord signup** | `GET /auth/discord` → consent → callback: create `users` (+ `email_verified` from Discord's `verified` claim, auto-provisioned profile, `user` role) + `oauth_accounts` row → logged in |
| **Email signup, Discord linked later** | logged-in `GET /auth/discord/link` → consent → callback inserts the `oauth_accounts` row for the current user |
| **Discord-first user wants a password** | logged-in `POST /auth/credentials` `{password}` → inserts their `credentials` row |
| **Discord login, email matches existing account** | lookup order: ① `oauth_accounts` by `(provider, provider_account_id)` → log in; ② `users` by email — **only if Discord says `verified: true`** → auto-link instead of duplicating; ③ otherwise create a fresh identity |
| **Change password** | `PATCH /auth/credentials` `{currentPassword,newPassword}` — every other device is logged out, the calling one survives |
| **Unlink Discord** | `DELETE /auth/connections/discord` — allowed only if a password (or another provider) remains |
| **Remove password** | `DELETE /auth/credentials` `{currentPassword}` — allowed only if a linked provider remains |

The auto-link-on-verified-email rule is exactly better-auth's behaviour; the
table layout doesn't change it, the service layer does.

## The OAuth round-trip

```
browser                    auth (:8080 via gateway)            Discord
   │ GET /api/v1/auth/discord       │                              │
   │◀─ 302 authorize URL + om_oauth (HMAC-signed state) cookie      │
   │───────────────────────────────────────────────────────────────▶│ consent
   │◀────────────────── 302 /api/v1/auth/discord/callback?code&state │
   │ GET callback (code + state + om_oauth cookie)  │                │
   │                          │ state signature/match/expiry check  │
   │                          │── POST /oauth2/token (form!) ──────▶│
   │                          │◀─ access_token ─────────────────────│
   │                          │── GET /users/@me ──────────────────▶│
   │                          │◀─ User object (snowflake id, …) ────│
   │◀─ 302 success + om_access/om_refresh cookies (login/signup) ────│
   │   (or 302 failure?error=provider_already_linked / …)           │
```

Errors reach the browser as **redirects** to the failure page with
`?error=<code>` — `oauth_state_mismatch` (forged/expired state),
`provider_already_linked`, `oauth_email_required` (no verified email on the
Discord account), `oauth_failed`. The `state` binding follows Discord's own
security guidance: the value travels to Discord *and* sits in an httpOnly
cookie; both must match at the callback.

## Guard rails

- **Never orphan an identity.** Every mutation (link/unlink/set-password)
  checks "does ≥1 login method remain?"
- **Never trust unverified emails for linking.** A Discord account with an
  unverified email matching yours is *not* you; no link happens.
- **One email per identity** (known limitation, same as v1). "Login email ≠
  notification email" or multi-email would need a `user_emails` redesign —
  not worth it for a marketplace.

## Versus better-auth's single `account` table

Identical capability — better-auth stores password logins as a row in the
same table (`provider_id='credential'`, `password` column). The split
version's advantages: "max one password" is a PK constraint instead of app
logic, and OAuth rows never carry a nullable `password` column. Same linking
flows either way.

---

Related: [data-model.md](data-model.md) · [roadmap.md](roadmap.md) (Phase C status)
