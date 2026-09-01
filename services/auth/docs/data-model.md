# Data model

> [auth](../README.md) › Data model

Every table this service owns, and the conventions behind them.

## Contents

- [Schema placement](#schema-placement)
- [Tables](#tables)
- [Entity relationship sketch](#entity-relationship-sketch)
- [Conventions](#conventions)

---

## Schema placement

All tables live in the Postgres schema **`auth`** inside `auth_db`
(db-per-service locally, schema-per-service on Supabase-style hosting).
Wired in three places, consistently:

- JDBC URL: `?currentSchema=auth` (`DatabaseConfig`)
- Flyway: `spring.flyway.schemas: auth`, `create-schemas: true`
- JPA: `spring.jpa.properties.hibernate.default_schema: auth`

## Tables

Source of truth: `src/main/resources/db/migration/V1__init.sql` +
`V2__moderation_outbox.sql` + `V3__verification_identifier.sql`.
v1 lineage: [`docs/V1-SCHEMAS.md`](../../../docs/V1-SCHEMAS.md).

| Table | Purpose | Notes vs v1 (better-auth) |
|---|---|---|
| `users` | identity | uuid PK (v1: text id), `deleted_at` soft delete, email unique |
| `user_profiles` | marketplace profile | `username` unique; `social_links`/`notification_preferences` are JSON strings in TEXT (v1-faithful) |
| `users_activity` | presence snapshot | owned here per v1 mapping; wired when presence integrates |
| `credentials` | password login | split out of v1's single `account` table; PK = `user_id` |
| `oauth_accounts` | Discord & co | split out of v1's single `account` table; `UNIQUE (provider, provider_account_id)` |
| `refresh_tokens` | sessions | **replaces v1 `session` table**; hash-only, `family_id` rotation, `rotated_from_id` chain, `user_agent`/`ip_address` device metadata (sessions UI) |
| `verification_tokens` | email verify / email change / password reset | typed, `identifier` carries the address the token acts on (V3), `used_at`, hash-only |
| `roles` | RBAC roles | seeded: `user`, `moderator`, `admin`, `owner` |
| `permissions` | RBAC permissions | seeded incl. cross-service ones (`catalogue.listing.write`, `admin.user.ban`, …) |
| `role_permissions` | role ↔ permission | composite unique |
| `user_roles` | user ↔ role | composite unique |
| `user_bans` | moderation (Phase E) | `banned_by`/`lifted_at`; partial index on active bans; enforced at login/refresh later |
| `user_warnings` | moderation (Phase E) | `warned_by`, reason required |
| `outbox_events` | Kafka outbox (events phase) | `topic` + `payload` jsonb; partial index on unpublished rows; relay wiring comes later |

Tables in `V1__init.sql`: 11 · `V2__moderation_outbox.sql` adds 3 → **14 total**.

Indexes worth knowing:

- `refresh_tokens(token_hash)` UNIQUE — the only lookup key
- `refresh_tokens(family_id)` — theft-response revocation
- `refresh_tokens(user_id) WHERE revoked_at IS NULL` (partial) — revoke-all-for-user
- `verification_tokens(token_hash)` — the only lookup key
- `user_bans(user_id) WHERE lifted_at IS NULL` (partial) — "is this user banned?"
- `outbox_events(created_at) WHERE published_at IS NULL` (partial) — relay polling

## Entity relationship sketch

```
users 1 ──── 0..1 credentials
  │  1 ──── 0..1 user_profiles
  │  1 ──── 0..1 users_activity
  │  1 ──── 0..N oauth_accounts
  │  1 ──── 0..N refresh_tokens ──┐ (self-ref: rotated_from_id)
  │  1 ──── 0..N verification_tokens
  │  1 ──── 0..N user_bans (banned_by → users, SET NULL)
  │  1 ──── 0..N user_warnings (warned_by → users, SET NULL)
  │  1 ──── 0..N user_roles ── N..1 roles ── 0..N role_permissions ── N..1 permissions
  └ (id referenced by other services' dbs — never joined across)
```

## Conventions

- uuid PKs (`gen_random_uuid()` in SQL, `@GeneratedValue(UUID)` in JPA)
- `TIMESTAMPTZ` audit columns (`created_at`, `updated_at`), `deleted_at`
  soft delete **only** on `users`
- JPA entities are validated against the migrations at boot
  (`ddl-auto: validate`) — Flyway is the source of truth, never auto-DDL
- Tables are plural (`users`, `roles`) — v1's `user`/`account`/`verification`
  collided with reserved words / read poorly

---

Related: [accounts.md](accounts.md) · [api.md](api.md)
