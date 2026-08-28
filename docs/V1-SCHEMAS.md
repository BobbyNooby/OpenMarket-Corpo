# v1 Schemas — Reference for the v2 Rebuild

The database schema from the **v1** OpenMarket monolith (TypeScript /
SvelteKit + Elysia + Drizzle ORM), copied verbatim in spirit from the old repo
to act as the domain contract for the polyglot v2 microservices.

- **Source:** `BobbyNooby/OpenMarket` — `packages/server/src/db/`
  (`schemas.ts`, `auth-schema.ts`, `rbac-schema.ts`)
- **Checked out at:** commit `f5348fa` (2026-04-17)
- **Purpose:** each v2 service owns a slice of this schema in its own
  Postgres DB. This doc is the single source of truth for what the v1 domain
  looked like so v2 contracts (`contracts/`) and per-service projections stay
  faithful.

---

## Enums

| Enum | Values |
|------|--------|
| `review_type` | `upvote`, `downvote` |
| `order_type` | `buy`, `sell` |
| `paying_type` | `each`, `total` |
| `listing_status` | `active`, `sold`, `paused`, `expired` |
| `report_target_type` | `listing`, `review`, `user` |
| `report_status` | `pending`, `resolved`, `dismissed` |
| `item_list_type` | `have`, `want` |
| `notification_type` | `new_message`, `new_review`, `listing_expired`, `listing_sold`, `role_changed`, `warning_received`, `report_resolved` |
| `theme_variant` | `light`, `dark` |

---

## Auth & Users → v2 **auth** service (`auth_db`)

### `user`
Standard better-auth user. PK is a text id.

| Column | Type | Constraints |
|--------|------|-------------|
| `id` | text | PK |
| `name` | text | not null |
| `email` | text | not null, unique |
| `email_verified` | boolean | default `false`, not null |
| `image` | text | |
| `created_at` | timestamp | default now, not null |
| `updated_at` | timestamp | default now, auto-update, not null |

### `session`
| Column | Type | Constraints |
|--------|------|-------------|
| `id` | text | PK |
| `expires_at` | timestamp | not null |
| `token` | text | not null, unique |
| `created_at` | timestamp | default now, not null |
| `updated_at` | timestamp | auto-update, not null |
| `ip_address` | text | |
| `user_agent` | text | |
| `user_id` | text | FK → `user.id`, cascade |

Index: `user_id`.

### `account`
OAuth / credential provider accounts (Discord, email+password).

| Column | Type | Constraints |
|--------|------|-------------|
| `id` | text | PK |
| `account_id` | text | not null |
| `provider_id` | text | not null |
| `user_id` | text | FK → `user.id`, cascade |
| `access_token` | text | |
| `refresh_token` | text | |
| `id_token` | text | |
| `access_token_expires_at` | timestamp | |
| `refresh_token_expires_at` | timestamp | |
| `scope` | text | |
| `password` | text | |
| `created_at` | timestamp | default now, not null |
| `updated_at` | timestamp | auto-update, not null |

Index: `user_id`.

### `verification`
One-time verification codes (email verification, password reset).

| Column | Type | Constraints |
|--------|------|-------------|
| `id` | text | PK |
| `identifier` | text | not null |
| `value` | text | not null |
| `expires_at` | timestamp | not null |
| `created_at` | timestamp | default now, not null |
| `updated_at` | timestamp | default now, auto-update, not null |

Index: `identifier`.

### `user_profiles`
Marketplace-specific user data that better-auth doesn't handle.

| Column | Type | Constraints |
|--------|------|-------------|
| `user_id` | text | PK, FK → `user.id`, cascade |
| `username` | text | not null, unique |
| `description` | text | |
| `bio` | text | |
| `social_links` | text | JSON string `{discord, twitter, ...}` |
| `accent_color` | text | hex color |
| `notification_preferences` | text | JSON string `Record<NotificationType, boolean>`, not null, default `{}` |
| `language` | text | BCP-47, not null, default `en` |
| `avatar_url` | text | custom avatar, overrides Discord CDN |

### `users_activity`
Presence / activity tracking.

| Column | Type | Constraints |
|--------|------|-------------|
| `user_id` | text | PK, FK → `user.id`, cascade |
| `is_active` | boolean | default `false`, not null |
| `last_activity_at` | timestamp | default now, not null |

---

## RBAC & Moderation → v2 **auth** + **admin** services

### `roles`
| Column | Type | Constraints |
|--------|------|-------------|
| `id` | text | PK |
| `name` | text | not null, unique |
| `description` | text | |

### `permissions`
| Column | Type | Constraints |
|--------|------|-------------|
| `id` | text | PK |
| `name` | text | not null, unique |
| `description` | text | |

### `role_permissions`
Join: role ↔ permission.

| Column | Type | Constraints |
|--------|------|-------------|
| `role_id` | text | FK → `roles.id`, cascade |
| `permission_id` | text | FK → `permissions.id`, cascade |

### `user_roles`
Join: user ↔ role.

| Column | Type | Constraints |
|--------|------|-------------|
| `user_id` | text | FK → `user.id`, cascade |
| `role_id` | text | FK → `roles.id`, cascade |

### `user_bans`
| Column | Type | Constraints |
|--------|------|-------------|
| `id` | uuid | PK, default random |
| `user_id` | text | FK → `user.id`, cascade |
| `banned_by` | text | FK → `user.id`, cascade |
| `reason` | text | |
| `banned_at` | timestamp | default now, not null |
| `expires_at` | timestamp | |

### `user_warnings`
| Column | Type | Constraints |
|--------|------|-------------|
| `id` | uuid | PK, default random |
| `user_id` | text | FK → `user.id`, cascade |
| `warned_by` | text | FK → `user.id`, cascade |
| `reason` | text | not null |
| `created_at` | timestamp | default now, not null |

### `audit_logs`
| Column | Type | Constraints |
|--------|------|-------------|
| `id` | uuid | PK, default random |
| `actor_id` | text | FK → `user.id`, cascade |
| `action` | text | not null, e.g. `user.ban`, `role.assign`, `report.resolve` |
| `target_type` | text | not null, e.g. `user`, `role`, `report`, `listing` |
| `target_id` | text | not null |
| `metadata` | jsonb | free-form |
| `created_at` | timestamp | default now, not null |

Indexes: `created_at` (desc), `action`, `actor_id`.

---

## Catalogue → v2 **catalogue** service (`catalogue_db`)

### `item_categories`
| Column | Type | Constraints |
|--------|------|-------------|
| `id` | uuid | PK, default random |
| `created_at` | timestamp | default now, not null |
| `name` | text | not null, unique |
| `slug` | text | not null, unique |
| `icon_url` | text | |

### `items`
Generic, catalog-wide item definitions (not user-specific).

| Column | Type | Constraints |
|--------|------|-------------|
| `id` | uuid | PK, default random |
| `created_at` | timestamp | default now, not null |
| `slug` | text | not null, unique |
| `name` | text | not null |
| `description` | text | |
| `wiki_link` | text | |
| `image_url` | text | |
| `category_id` | uuid | FK → `item_categories.id`, set null on delete |

### `currencies`
Virtual currencies as tradable entities.

| Column | Type | Constraints |
|--------|------|-------------|
| `id` | uuid | PK, default random |
| `created_at` | timestamp | default now, not null |
| `slug` | text | not null, unique |
| `name` | text | not null |
| `description` | text | |
| `wiki_link` | text | |
| `image_url` | text | |

---

## Marketplace / Listings → v2 **catalogue** service

### `listings`
A market order. Requests **either** an item **or** a currency (one set, other null).

| Column | Type | Constraints |
|--------|------|-------------|
| `id` | uuid | PK, default random |
| `created_at` | timestamp | default now, not null |
| `author_id` | text | FK → `user.id`, cascade |
| `requested_item_id` | uuid | FK → `items.id`, cascade |
| `requested_currency_id` | uuid | FK → `currencies.id`, cascade |
| `amount` | integer | default `1`, not null |
| `order_type` | `order_type` | not null (`buy`/`sell`) |
| `paying_type` | `paying_type` | default `each`, not null |
| `status` | `listing_status` | default `active`, not null |
| `expires_at` | timestamp | |

Indexes: `(status, created_at)`, `(status, expires_at)`, `author_id`, `requested_item_id`, `requested_currency_id`.

### `listing_offered_items`
Many-to-many: what a listing offers in exchange.

| Column | Type | Constraints |
|--------|------|-------------|
| `id` | uuid | PK, default random |
| `listing_id` | uuid | FK → `listings.id`, cascade |
| `item_id` | uuid | FK → `items.id`, cascade |
| `amount` | integer | default `1`, not null |

Index: `listing_id`.

### `listing_offered_currencies`
Many-to-many: currencies offered in exchange.

| Column | Type | Constraints |
|--------|------|-------------|
| `id` | uuid | PK, default random |
| `listing_id` | uuid | FK → `listings.id`, cascade |
| `currency_id` | uuid | FK → `currencies.id`, cascade |
| `amount` | integer | default `1`, not null |

Index: `listing_id`.

### `trades`
Completed trades, with a frozen JSON snapshot of the listing.

| Column | Type | Constraints |
|--------|------|-------------|
| `id` | uuid | PK, default random |
| `seller_id` | text | FK → `user.id`, cascade |
| `buyer_id` | text | FK → `user.id`, set null |
| `listing_snapshot` | text | JSON of frozen listing data, not null |
| `completed_at` | timestamp | default now, not null |
| `created_at` | timestamp | default now, not null |

Indexes: `seller_id`, `buyer_id`, `completed_at`.

### `watchlist`
Composite PK (user, listing).

| Column | Type | Constraints |
|--------|------|-------------|
| `user_id` | text | PK (composite), FK → `user.id`, cascade |
| `listing_id` | uuid | PK (composite), FK → `listings.id`, cascade |
| `created_at` | timestamp | default now, not null |

### `user_item_lists`
Per-user "have"/"want" lists.

| Column | Type | Constraints |
|--------|------|-------------|
| `id` | uuid | PK, default random |
| `user_id` | text | FK → `user.id`, cascade |
| `list_type` | `item_list_type` | not null (`have`/`want`) |
| `item_id` | uuid | FK → `items.id`, cascade |
| `currency_id` | uuid | FK → `currencies.id`, cascade |
| `created_at` | timestamp | default now, not null |

Indexes: `(user_id, list_type)`, `item_id`, `currency_id`.

---

## Reviews & Reputation → v2 **auth** service

### `profile_reviews`
Upvote/downvote reviews feeding the trust score.

| Column | Type | Constraints |
|--------|------|-------------|
| `id` | uuid | PK, default random |
| `created_at` | timestamp | default now, not null |
| `profile_user_id` | text | FK → `user.id`, cascade (reviewed user) |
| `voter_user_id` | text | FK → `user.id`, cascade (reviewer) |
| `type` | `review_type` | not null (`upvote`/`downvote`) |
| `comment` | text | |

Indexes: `profile_user_id`, `voter_user_id`.

---

## Messaging → v2 **messaging** service (`messaging_db`)

### `conversations`
| Column | Type | Constraints |
|--------|------|-------------|
| `id` | uuid | PK, default random |
| `created_at` | timestamp | default now, not null |
| `updated_at` | timestamp | default now, not null |
| `listing_id` | uuid | FK → `listings.id`, set null |

Indexes: `listing_id`, `updated_at`.

### `conversation_participants`
Composite PK (conversation, user).

| Column | Type | Constraints |
|--------|------|-------------|
| `conversation_id` | uuid | PK (composite), FK → `conversations.id`, cascade |
| `user_id` | text | PK (composite), FK → `user.id`, cascade |
| `joined_at` | timestamp | default now, not null |
| `last_read_at` | timestamp | |

Index: `user_id`.

### `messages`
| Column | Type | Constraints |
|--------|------|-------------|
| `id` | uuid | PK, default random |
| `conversation_id` | uuid | FK → `conversations.id`, cascade |
| `sender_id` | text | FK → `user.id`, cascade |
| `content` | text | not null |
| `created_at` | timestamp | default now, not null |
| `edited_at` | timestamp | |
| `is_deleted` | boolean | default `false`, not null |

Indexes: `(conversation_id, created_at)`, `sender_id`.

---

## Notifications → v2 **presence** service (`notif_db`)

### `notifications`
| Column | Type | Constraints |
|--------|------|-------------|
| `id` | uuid | PK, default random |
| `user_id` | text | FK → `user.id`, cascade |
| `type` | `notification_type` | not null (7 types) |
| `title` | text | not null |
| `body` | text | |
| `link` | text | |
| `is_read` | boolean | default `false`, not null |
| `created_at` | timestamp | default now, not null |

Index: `(user_id, is_read, created_at)`.

---

## Reports → v2 **admin** service (`admin_db`)

### `reports`
| Column | Type | Constraints |
|--------|------|-------------|
| `id` | uuid | PK, default random |
| `reporter_id` | text | FK → `user.id`, cascade |
| `target_type` | `report_target_type` | not null (`listing`/`review`/`user`) |
| `target_id` | text | not null |
| `reason` | text | not null |
| `status` | `report_status` | default `pending`, not null |
| `created_at` | timestamp | default now, not null |
| `resolved_by` | text | FK → `user.id`, set null |
| `resolved_at` | timestamp | |

Indexes: `status`, `(target_type, target_id)`.

---

## Analytics → v2 **admin** service (insights)

### `analytics_events`
| Column | Type | Constraints |
|--------|------|-------------|
| `id` | uuid | PK, default random |
| `event_type` | text | not null |
| `user_id` | text | FK → `user.id`, set null |
| `session_id` | text | |
| `metadata` | text | JSON string |
| `path` | text | |
| `referrer` | text | |
| `created_at` | timestamp | default now, not null |

Indexes: `(event_type, created_at)`, `(user_id, created_at)`, `session_id`.

---

## Assets & Site Config → v2 **assets** + **admin** services

### `uploads`
Locally-hosted user files. Everything else references uploads by id.

| Column | Type | Constraints |
|--------|------|-------------|
| `id` | uuid | PK, default random |
| `user_id` | text | FK → `user.id`, cascade |
| `filename` | text | not null |
| `mime_type` | text | not null |
| `size_bytes` | integer | not null |
| `width` | integer | not null |
| `height` | integer | not null |
| `created_at` | timestamp | default now, not null |

Index: `(user_id, created_at)`.

### `site_assets`
Branded image slots (hero, login illustration, etc.).

| Column | Type | Constraints |
|--------|------|-------------|
| `slot` | text | PK |
| `upload_id` | uuid | FK → `uploads.id`, set null |
| `url` | text | not null |
| `updated_at` | timestamp | default now, not null |

### `site_config`
Non-theme key/value branding: `site_name`, `tagline`, logo URL, footer text, links.

| Column | Type | Constraints |
|--------|------|-------------|
| `key` | text | PK |
| `value` | text | not null, default `''` |
| `updated_at` | timestamp | default now, not null |
| `updated_by` | text | FK → `user.id`, set null |

### `site_theme`
Per-variant theme variables. Composite PK (variant, variable).

| Column | Type | Constraints |
|--------|------|-------------|
| `variant` | `theme_variant` | PK (composite), not null (`light`/`dark`) |
| `variable` | text | PK (composite), not null (`primary`, `background`, ...) |
| `value` | text | not null, HSL triplet |
| `updated_at` | timestamp | default now, not null |
| `updated_by` | text | FK → `user.id`, set null |

---

## v1 → v2 service mapping

| v1 table(s) | v2 owning service | v2 DB |
|-------------|-------------------|-------|
| `user`, `session`, `account`, `verification`, `user_profiles`, `users_activity`, `profile_reviews` | auth | `auth_db` |
| `roles`, `permissions`, `role_permissions`, `user_roles`, `user_bans`, `user_warnings` | auth (RBAC) | `auth_db` |
| `item_categories`, `items`, `currencies`, `listings`, `listing_offered_*`, `trades`, `watchlist`, `user_item_lists` | catalogue | `catalogue_db` |
| `conversations`, `conversation_participants`, `messages` | messaging | `messaging_db` |
| `notifications` | presence | `notif_db` |
| `reports`, `audit_logs`, `analytics_events`, `site_config`, `site_theme` | admin | `admin_db` |
| `uploads`, `site_assets` | assets | `asset_db` |