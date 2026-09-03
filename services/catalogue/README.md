# catalogue — Catalogue & Listings

The **catalogue** service owns the marketplace catalog: items, currencies,
categories, listings, offers, and trades.

- **Stack:** C# / ASP.NET Core (.NET 10), port 8081
- **Database:** PostgreSQL (`catalogue_db`), EF Core + Npgsql, migrations on
  startup under an advisory lock
- **Identity:** JWTs minted by the auth service (RS256, JWKS fetched lazily,
  `om_access` cookie accepted with Bearer-wins). Every mutation ALSO calls
  auth's `IntrospectToken` gRPC for the ban check — **fail-closed 503** when
  auth is unreachable, unparseable, or lying about identity.

## Current state

Shipped (see `Endpoints/` — all under `/api/v1/catalogue`):

- **Catalog:** items / currencies / categories CRUD, owner-or-admin gated,
  retire (soft-hide, never delete), slug generation per-table, public browse
  with filters (q, category, orderType, status, requested/offered item or
  currency, author), sort (`newest`, `ending_soon`), clamped pagination.
- **Listings:** create (XOR: exactly one of requested item/currency, DB CHECK
  + app validation), **full-replace PATCH** (the body states the entire
  desired listing — switching the requested kind is an ordinary update;
  OrderType/PayingType are immutable post-create), pause/resume/cancel with
  an honest state machine, browse/search, per-author listing cap (200).
- **Accept (deliberately FCFS):** buyer-driven, first valid accept wins via an
  atomic conditional UPDATE; competing accepts → 409; lapsed-but-unswept
  listings → 410; author self-accept → 409. The whole lot freezes a jsonb
  snapshot server-side at accept; the listing survives as a closed record.
  *Design decision:* v1 let sellers pick a buyer from chat contacts — v2 is
  first-come-first-served by choice; revisit only with a
  pending-accept→confirm state machine, not casually.
- **Idempotency:** `Idempotency-Key` header (≤100 chars). Create: same key +
  same body → 200 replay; same key + different body → 409
  `idempotency_key_reused`. Accept: keyless accepts synthesize a deterministic
  per-(buyer, listing) key, so retries replay and keys never collide; a key
  reused on a *different* listing → 409.
- **Expiry:** `ExpiryScanner` (hosted service, 60s) flips lapsed listings with
  a DB-clock predicate + `pg_try_advisory_lock` (replica-safe); outbox events
  written atomically with the flip.
- **Events:** every domain mutation writes an outbox row
  (`listing.created/updated/cancelled/sold/expired`). **Kafka relay is not
  built yet** — `/health/ready` reports 503 `degraded` if unpublished outbox
  depth exceeds 10k.
- **Me:** watchlist (cap 500, ban-gated writes), have/want item-lists
  (cap 200), my trades, my listings.
- **Tests:** 30 xUnit tests (Testcontainers Postgres + WebApplicationFactory)
  — CI runs `dotnet test Catalogue.Tests` explicitly.

## Local dev

```bash
# from repo root
make postgres
make catalogue
# or: cd services/catalogue && dotnet run
```

Requires `DATABASE_URL` (defaults to `catalogue_db`). Additional env vars:
`AUTH_URL` (JWKS, default `http://localhost:8080`), `AUTH_GRPC_URL`
(ban-check gRPC, default `http://localhost:9090`), `GRPC_INTERNAL_SECRET`
(must match auth's), `DATABASE_SSLMODE`. In **Production** the service
refuses to start on dev-default secrets; the compose stack pins itself to
Development explicitly.
