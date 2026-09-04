# Architecture

> [catalogue](../README.md) › Architecture

How the catalogue service fits into the OpenMarket v2 fleet, what it owns,
and how the marketplace's core mechanics actually work.

## Contents

- [Position in the fleet](#position-in-the-fleet)
- [Request path](#request-path)
- [Database-per-service posture](#database-per-service-posture)
- [Domain and data model](#domain-and-data-model)
- [The FCFS accept](#the-fcfs-accept)
- [Idempotency](#idempotency)
- [Events and the outbox](#events-and-the-outbox)
- [Expiry scanner](#expiry-scanner)
- [Endpoint inventory](#endpoint-inventory)
- [Configuration](#configuration)

---

## Position in the fleet

Catalogue is one of seven services in the polyglot v2 rebuild. It owns the
marketplace catalog and nothing else: items, currencies, categories,
listings, offers, trades, watchlists and have/want item-lists. Users exist
only as opaque uuid columns (`AuthorId`, `SellerId`, `BuyerId`, `UserId`) —
there is no cross-service foreign key into auth's database
(`CatalogueDbContext.cs:24-27`).

```
   SvelteKit ──────▶ Go gateway ──────▶ catalogue :8081
                     (BFF, :3000)          │  validates the JWT locally against
                                           │  auth's JWKS  (Program.cs:44-85)
                                           │  + calls IntrospectToken gRPC on
                                           ▼  every mutation (ban check)
                                      catalogue_db (Postgres)
```

- **Who calls it:** the Go gateway REST-proxies `/api/v1/catalogue/*`.
  Catalogue **enforces its own authentication** (JWT validation plus the
  introspection ban check) rather than trusting the gateway's edge —
  deliberate, because direct `:8081` access bypasses the gateway
  entirely, so the mutations gate themselves (`Endpoints/Edge.cs:5-11`).
- **What it consumes from auth, and what gates what:**
  - **JWKS (`GET /.well-known/jwks.json`)** gates *authentication*.
    Every request runs through the real JwtBearer pipeline, which fetches
    auth's RSA public key lazily, caches it for one hour, and fails
    closed — a fetch failure yields no keys, so tokens 401 rather than
    erroring the request (`Program.cs:62-64, 162-204`).
  - **`IntrospectToken` gRPC (auth :9090, shared secret)** gates
    *mutations only*. It is the ban check: a cryptographically valid but
    revoked/banned token still passes the signature check, and only
    introspection can see that. Every mutating handler calls
    `Edge.RequireLiveAsync` first; reads (browse, listing detail) are
    anonymous and skip it (`Edge.cs:5-11, 33-52`).
  - In short: a valid, parseable-sub JWT gets you read access; a valid
    JWT **plus a live introspection verdict** gets you write access.
- **What it emits:** outbox rows for listing/trade domain events — and,
  honestly, nothing else. **There is no Kafka relay for catalogue yet.**
  See [Events and the outbox](#events-and-the-outbox).

## Request path

1. The browser sends the `om_access` cookie to the gateway; the gateway
   forwards `Authorization: Bearer <jwt>` to catalogue. Catalogue also
   accepts the `om_access` cookie directly (dev harness) — the header
   wins (`Program.cs:67-75`, `Edge.cs:14-19`).
2. JwtBearer validates the signature (RS256 only — `ValidAlgorithms` is
   pinned, `Program.cs:58`), issuer (`auth`), audience (`openmarket`),
   with a 30 s clock skew (`Program.cs:59`), and `OnTokenValidated`
   requires a **parseable Guid `sub`** or the token fails
   (`Program.cs:78-83`).
3. For a mutation, `Edge.RequireLiveAsync` extracts the token (header or
   cookie), calls `IntrospectToken` over gRPC with a 2 s deadline
   (`Auth/Introspection.cs:42-43`), and maps the verdict:
   unreachable/unparseable → **503 fail-closed**; `active=false` →
   **403** (`Edge.cs:39-48`). The identity is stashed in
   `HttpContext.Items`.
4. The handler derives the user from the validated token's `sub` claim
   (`Edge.Sub`, `Edge.cs:62-63`) — never from the body or any header.
5. Writes share a transaction with their outbox row; `SaveChangesAsync`
   commits both atomically.

## Database-per-service posture

- Postgres database `catalogue_db`, owned by this service alone. Migrations
  (EF Core) run at startup under a Postgres advisory lock so a second
  replica waits instead of racing (`Program.cs:113-120`,
  `Infrastructure/MigrationsRunner.cs`).
- `DATABASE_URL` is parsed libpq-style by
  `Infrastructure/DatabaseUrl.cs`; a remote host defaults to
  `sslmode=require`, loopback to `disable`, and `DATABASE_SSLMODE`
  overrides both. Unrecognized sslmode values fail fast
  (`DatabaseUrl.cs:41-46`).
- User references are **bare uuid columns with no FK and no cascade** —
  auth's users live in `auth_db`; lifecycle arrives (eventually) via
  `user.deleted` / `user.banned` events (`CatalogueDbContext.cs:24-27`).
  Intra-service FKs are real: shared definitions are `Restrict` (retiring
  an item must never mass-delete listings — the v1 cascade was the
  defacement risk, `CatalogueDbContext.cs:69-76`), offer lines and
  watchlist entries `Cascade` with their listing
  (`CatalogueDbContext.cs:88, 125`), and `Trades.ListingId` is `Restrict`
  — a trade outlives any attempt to remove its listing
  (`CatalogueDbContext.cs:116-117`).

## Domain and data model

Ten tables (from `Migrations/20260902223601_InitialCreate.cs`; enums stored
as text, timestamptz everywhere):

| Table | Purpose | Key indexes / constraints |
|---|---|---|
| `Categories` | item groupings | unique `Slug`; `RetiredAt` soft-hide |
| `Currencies` | currency definitions | unique `Slug` |
| `Items` | item definitions | unique `Slug`; FK `CategoryId` Restrict |
| `Listings` | the marketplace rows | CHECK `requested_xor` (exactly one of item/currency), CHECK `Amount > 0`; `(Status, CreatedAt)`, `(Status, ExpiresAt)`, `AuthorId`, `RequestedItemId/CurrencyId`; **unique `(AuthorId, IdempotencyKey)` filtered `<> ''`** (migration :270-274) |
| `OfferedItems` / `OfferedCurrencies` | offer lines per listing | FKs to Listings (Cascade) + Items/Currencies (Restrict); CHECK `Amount > 0`; indexes on both FKs |
| `Trades` | one row per completed deal; the terms live in a server-frozen jsonb `Snapshot` | indexes `SellerId`, `BuyerId`, `CompletedAt`, `ListingId`; **unique `(AcceptedById, IdempotencyKey)` filtered `<> ''`** (migration :323-327) |
| `Watchlist` | user↔listing watch entries | PK `(UserId, ListingId)`; FK ListingId Cascade |
| `UserItemLists` | have/want lists | CHECK `xor`; unique `(UserId, ListType, ItemId)` and `(UserId, ListType, CurrencyId)`, each filtered non-null (migration :364-376) |
| `Outbox` | domain events | partial index on `PublishedAt` where null — the relay-ready unpublished-first scan (`CatalogueDbContext.cs:148`) |

The `Listings` row carries a **`xmin` row-version token**
(`CatalogueDbContext.cs:158-162`): a stale tracked update (PATCH racing
accept's raw `ExecuteUpdate`) throws `DbUpdateConcurrencyException`
instead of silently rewriting a sold listing. `xmin` needs no migration —
it exists on every Postgres row.

## The FCFS accept

`POST /listings/{id}/accept` is buyer-driven and deliberately
first-come-first-served (v1 let sellers pick a buyer from chat contacts —
v2 chose FCFS; revisit only with a pending-accept→confirm state machine,
per `README.md:31-33`).

Mechanics (`ListingEndpoints.cs:269-373`):

1. **Gates:** ban introspection, then self-accept → 409 `self_accept`
   (:302-303); status `Expired` → 410 (:304-305); **lapsed-but-unswept**
   (expiresAt in the past though the scanner hasn't flipped the row yet)
   → 410 (:307-309).
2. **The atomic gate:** inside a transaction (:311), a **conditional
   `ExecuteUpdate`** flips `Active → Sold` only where `Status = Active AND
   (ExpiresAt IS NULL OR ExpiresAt > now)` (:315-320). Exactly one
   concurrent accepter sees rowcount 1; everyone else races the rowcount
   and gets 409 `listing_sold` or 410 after a reload (:321-328). The
   in-predicate expiry check closes the check-vs-update race with the
   sweep.
3. **Snapshot:** the whole lot is frozen **server-side** into a jsonb
   snapshot (amount, requested item/currency, all offer lines, seller/
   buyer/accepter ids, completedAt) (:334-350); the listing survives as a
   closed record. The buyer/seller assignment derives from `OrderType`
   (:330-332).
4. **Commit:** the `Trade` row and the `listing.sold` outbox event commit
   in the same transaction as the flip (:361-370). The unique
   `(AcceptedById, IdempotencyKey)` index is the DB backstop.

**Known bounded race** (`README.md:34-39`): a seller PATCH committing in
the narrow window between accept's snapshot-read and its status flip can
leave the frozen snapshot on pre-edit terms while the sold row shows
post-edit terms. Bounded on purpose — both actions are the seller's own
and the snapshot remains the system of record. The `xmin` token guards
the dangerous direction (stale editor vs sold listing), not this one.

## Idempotency

`Idempotency-Key` header (≤100 chars, else 400).

| Endpoint | Key present | Behavior |
|---|---|---|
| `POST /listings` | yes | **Replay-first** (:115-140): same key + same body → `200 {replay: true}` even if catalog state has since drifted (item retired, expiry passed, cap now full); same key + any body difference → 409 `idempotency_key_reused`. Comparison is parse-safe — a garbage enum string is a mismatch, not a 500. |
| `POST /listings` | no | normal create; empty key never enters the unique index (filtered `<> ''`) |
| `POST /listings/{id}/accept` | yes | replay by `(AcceptedById, IdempotencyKey)` on `Trades` (:283-294); key bound to THIS listing — reused on a different listing → 409 |
| `POST /listings/{id}/accept` | no | a **deterministic key is synthesized** `accept:{buyerId:N}:{listingId:N}` (:281), so keyless retries replay and empty keys can never collide on the unique index |

## Events and the outbox

Every domain mutation writes an outbox row in the same DB transaction as
the change (`ListingEndpoints.cs:167, 209, 263, 362-368, 439`;
`ExpiryScanner.cs:73-77`):

| Topic | Emitted by |
|---|---|
| `listing.created` | create |
| `listing.updated` | PATCH |
| `listing.cancelled` | cancel |
| `listing.sold` | accept |
| `listing.expired` | ExpiryScanner sweep |

**Honest status: no relay exists for catalogue.** Rows are written with
`PublishedAt = NULL` and nothing publishes them — the outbox is currently
a growing dead-letter pile, by design until the Kafka relay lands
(`README.md:48-51`). The safety net is readiness honesty:
`GET /health/ready` counts unpublished rows and answers **503 `degraded`**
once depth exceeds 10,000, warning loudly in the payload while staying
honest about the degradation (`Program.cs:123-143`). This is also why
listings carry a per-author cap of 200 — uncapped, one account looping
create/PATCH would flood readiness (`ListingEndpoints.cs:142-149`).

## Expiry scanner

`Infrastructure/ExpiryScanner.cs`, a hosted service registered at
`Program.cs:92`:

- Steady-state sweep every 60 s, plus a catch-up sweep on boot to cover
  downtime (:16-41).
- Takes `pg_try_advisory_lock` (lock key `0x63617465`); a second replica
  simply skips its tick (:47-49).
- One UPDATE with a **DB-clock predicate** (`ExpiresAt <= now()`) flips
  `Active → Expired` and RETURNS the rows; outbox rows are inserted in
  the **same transaction** (:52-83). DB `now()` in the predicate avoids
  app-clock skew — note the accept path still uses the app clock
  (see [security.md](security.md#known-gaps)).

## Endpoint inventory

All under `/api/v1/catalogue`. Auth column: `anon` = AllowAnonymous;
`live` = JWT + introspection (`Edge.RequireLiveAsync`); `admin` =
JWT + introspection + admin-or-owner role (`Edge.RequireCatalogAdminAsync`,
`Edge.cs:54-60`).

| Method & path | Auth | Success | Errors |
|---|---|---|---|
| `GET /health/live` · `GET /health/ready` · `GET /` | anon | 200 | 503 `degraded` / `db_unreachable` (ready) |
| `GET /listings/` (browse/search) | anon | 200 | 400 `validation_failed` (bad status/orderType/sort) |
| `GET /listings/{id}` | anon | 200 | 404 `not_found` |
| `POST /listings` | live | 201 | 400 `validation_failed` / `unknown_item` / `unknown_currency`; 409 `idempotency_key_reused` / `listing_cap_reached` |
| `PATCH /listings/{id}` (full-replace) | live | 200 | 404; 409 `listing_not_editable` / `listing_conflict` (xmin); 400s as create |
| `POST /listings/{id}/pause` | live | 200 | 404; 409 `conflict` (not active) |
| `POST /listings/{id}/resume` | live | 200 | 404; 409 `conflict`; 410 `listing_expired` |
| `POST /listings/{id}/cancel` | live | 200 | 409 `conflict` (not active/paused) |
| `POST /listings/{id}/accept` | live | 201 | 409 `self_accept` / `idempotency_key_reused` / `listing_sold`; 410 `listing_expired` |
| `GET /listings/trades/{id}` | live | 200 | 404 (unknown **or not yours** — participant-only) |
| `GET /listings/me/trades` | live | 200 | — |
| `GET /listings/me/listings` | live | 200 | — |
| `GET /categories` · `/items` · `/items/{slug}` · `/currencies` · `/currencies/{slug}` | anon | 200 | 404 |
| `POST /categories` · `/items` · `/currencies` | admin | 201 | 400 `validation_failed` / `unknown_category`; 409 `slug_taken` |
| `POST /{items\|categories\|currencies}/{slug}/retire` | admin | 200 | 404 |
| `GET /me/watchlist` | JWT only* | 200 | — |
| `PUT /me/watchlist/{listingId}` | live | 200 | 404; 409 `watchlist_full` (cap 500) |
| `DELETE /me/watchlist/{listingId}` | live | 200 | 404 |
| `GET /me/item-lists` | JWT only* | 200 | 400 `validation_failed` |
| `POST /me/item-lists` | live | 201 | 400 `validation_failed` / `unknown_item` / `unknown_currency`; 409 `item_lists_full` (cap 200) / `already_listed` |
| `DELETE /me/item-lists/{id}` | live | 204 | 404 |

\* The `/me` GET handlers call only `Edge.Sub` — no introspection, and
`Edge.Sub` never sees an unparseable sub because `OnTokenValidated`
already rejected such tokens (`Program.cs:78-83`). Reads being public to
banned users is policy, not an oversight (`Edge.cs:7-11`) — but the
unbounded-read concern is tracked in [security.md](security.md#known-gaps).

Error envelope: `{ code, message, field? }` via `Endpoints/Envelope.cs`;
unhandled exceptions log server-side and answer a generic
`500 internal_error` with no stack trace (`Program.cs:103-111`).

Browse semantics: default filter is `status=active`; filters q (escaped
ILIKE), category, orderType, requested/offered item or currency, authorId;
sort `newest` (default) or `ending_soon`; pagination clamped to
limit ≤ 50, offset ≤ 10,000 (`ListingEndpoints.cs:32-86`).

## Configuration

Env-first, localhost defaults for bare `dotnet run`
(`Program.cs:15-23`). There are **no appsettings files** — everything
comes from environment variables (tests inject `DATABASE_URL` as a
setting through the same `IConfiguration` path).

| Var | Default | What it does |
|---|---|---|
| `DATABASE_URL` | — (falls back to `POSTGRES_*` parts → localhost:5432/`catalogue_db`) | libpq-style connection string (`DatabaseUrl.cs`) |
| `POSTGRES_HOST/PORT/USER/PASSWORD` | localhost / 5432 / om / devpassword123 | fallbacks when `DATABASE_URL` is unset |
| `DATABASE_SSLMODE` | `auto` (remote→require, loopback→disable) | explicit override; only `disable\|require\|prefer` accepted, else fail-fast |
| `AUTH_URL` | `http://localhost:8080` | auth's JWKS base — local JWT validation |
| `AUTH_GRPC_URL` | `http://localhost:9090` | auth's `IntrospectToken` gRPC (ban check) |
| `GRPC_INTERNAL_SECRET` | `dev-internal-secret` | shared secret on every introspection call |
| `ASPNETCORE_ENVIRONMENT` | — | when `Production`, the service **refuses to start** if `GRPC_INTERNAL_SECRET` or `POSTGRES_PASSWORD` is unset or still the dev default (`Program.cs:27-35`) |

Not read by this service (verified against the source): `KAFKA_BROKERS`
and `OTLP_ENDPOINT` — there is no Kafka producer and no OTLP wiring in
catalogue yet; the compose file may set them, but nothing consumes them.

---

Related: [security.md](security.md) · [testing.md](testing.md) ·
fleet-wide decisions in [`docs/ARCHITECTURE.md`](../../../docs/ARCHITECTURE.md)
