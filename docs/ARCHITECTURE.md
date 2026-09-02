# openmarket-corpo — Architecture Notes

This doc expands the README with the distributed-systems and platform
engineering decisions that shape the build. The product (OpenMarket) is the
same as v1; v2 is a greenfield rebuild whose point is the architecture.

## Design goals

- **Polyglot by design.** Every backend service is a different enterprise
  language: Go, Java Spring Boot, C# ASP.NET Core, Python FastAPI. TypeScript is
  confined to the Next.js frontend.
- **Database-per-service.** Each service owns its PostgreSQL database. No
  cross-service reads.
- **Contract-first across languages.** Public API = OpenAPI. Internal sync
  calls = gRPC + protobuf. Async events = Kafka + protobuf + Schema Registry.
- **Ship features first, platform second.** Phases 1–3 prove the domain works
  end-to-end and produce the live URL (M3). Phase 4 layers production-shaped
  infrastructure on top without touching service code.

## Service map

| Service | Stack | Store | Responsibility |
|---------|-------|-------|----------------|
| Gateway / BFF | Go | — | Public entry, WS termination, JWT validation, rate limit, gRPC client to services, DTO aggregation |
| Auth & Users | Java Spring Boot | `auth_db` | OAuth/email+password, JWT issue, RBAC, bans/warnings, profiles, reviews + trust score |
| Catalogue | C# ASP.NET Core | `catalogue_db` | Items, currencies, categories, listings, offers, trades, search, watchlist, expiry scheduler |
| Messaging | Go | `messaging_db` | Conversations, messages, typing, unread, per-conversation WS fan-in/out |
| Presence & Notifications | Python FastAPI | `notif_db` + Redis | Online status, Redis pub/sub WS fan-out, notifications |
| Assets & Images | Python FastAPI | `asset_db` + MinIO | Uploads, Pillow resize/WebP, media library |
| Admin & Moderation | Java Spring Boot | `admin_db` | Reports, audit log, site config, analytics/insights |

## Communication patterns

| Caller | Callee | Protocol | Format | Notes |
|--------|--------|----------|--------|-------|
| Browser | Gateway | REST + WS | JSON | Public contract |
| Gateway | Auth / Catalogue / Admin | gRPC | protobuf | Internal sync calls; same `.proto` as events |
| Gateway | Messaging / Presence | WS / HTTP | JSON | WS for real-time fan-out |
| Messaging / Auth / Catalogue / Admin | Kafka | publish | protobuf | Async domain events |
| Presence / Admin / Auth | Kafka | subscribe | protobuf | Projections, side effects |
| All services | Docker / K8s | HTTP | — | Liveness/readiness probes stay HTTP |

## Event topics

`user.created`, `user.profile.updated`, `user.banned`, `listing.created`,
`listing.sold`, `listing.expired`, `message.created`, `message.deleted`,
`review.created`, `notification.created`, `report.created`,
`report.resolved`, `user.deleted`.

All events use protobuf schemas stored in `contracts/` and are enforced by a
Schema Registry.

## Locked-in decisions

1. **JWT stateless sessions.** Gateway validates signatures. Revocation via a
   Redis blocklist fed by `user.banned` events.
2. **Contract-first.** OpenAPI (public) + gRPC/protobuf (internal sync) +
   protobuf + Schema Registry (async events).
3. **No cross-service DB reads.** Services keep local projections from Kafka.
4. **Outbox pattern.** Any service that writes to Postgres and publishes to
   Kafka does both through an outbox table + relay, so the write and publish are
   atomic.
5. **Idempotent consumers + idempotency keys.** Kafka consumers dedupe by event
   id; HTTP mutations accept an idempotency key.
6. **Eventual consistency.** Trust score updates from `listing.sold`, GDPR
   deletion as a `user.deleted` saga.

## Internal gRPC (decided)

Gateway→service sync calls go **gRPC-first**: auth speaks gRPC from day one —
no REST-proxy-then-migrate stage. The first contract is `om.auth.v1.UserService`
(`GetUserById` unary + `StreamUsersByIds` server-streaming), which will live in
`contracts/proto/` (buf-managed; generated stubs committed initially). Auth
serves it on `:9090` beside HTTP as a thin adapter over the existing service
layer. The gateway is a trusted internal caller passing verified
user-id/role metadata; mTLS hardening stays deferred. Catalogue (C#) and admin
(Java) migrate during the Phase 4 fast-follow. Messaging/presence stay WS+JSON
(fan-out), and async service traffic stays Kafka+protobuf — same `.proto`
skills, different transport.

## Platform / infra fast-follow (Phase 4)

These are added after M3 without changing service business logic:

- **Internal gRPC** — auth done (gRPC-first, see above); migrate catalogue +
  admin gateway→service calls here.
- **Schema Registry** — enforce event schema versions.
- **Outbox relay** — background worker that polls outbox tables and publishes.
- **Idempotency middleware** — gateway stores keys, returns cached responses.
- **Testcontainers** — integration tests run against real Postgres/Kafka/Redis.
- **MinIO** — S3-compatible object storage for assets.
- **Pact** — contract tests between gateway and each service.
- **Load balancer + replicas** — health-gated on `/health/ready`.
- **PGBouncer** — connection pooling in front of Postgres.
- **Read replica** — offloads analytics/insights reads.
- **Prometheus/Grafana/Loki** — metrics, dashboards, log aggregation.
- **CI/CD** — GitHub Actions builds and deploys per service.
- **Circuit breaker / retry / timeout** — gateway resilience.

## Scaling model

One public entry point; everything else internal. Stateless services scale
horizontally; state lives in Postgres, Kafka, Redis. The single Postgres
instance is the honest write ceiling. The path up: PGBouncer → read replica →
promote hot DBs to their own instances. Write-sharding and multi-primary are
know-only.

## Frontend

**Next.js (App Router, React 19)** — decision made 2026-09-02: the new
frontend is Next.js rather than carrying v1's SvelteKit forward; v1's patterns
(domain routes, Tailwind, shadcn-style components) port over, components
rewrite Svelte → React. It only talks to the Go gateway. Frontend types will be
generated from the gateway's OpenAPI spec (`openapi-typescript` / Orval),
replacing v1's end-to-end Drizzle/Eden type chain.

| v1 (SvelteKit)          | v2 (Next.js)                      |
|-------------------------|-----------------------------------|
| file routes             | App Router (`src/app/`)           |
| `+page.server.ts` load  | RSC fetch / Server Actions        |
| Svelte stores           | TanStack Query / React state      |
| bits-ui + mode-watcher  | shadcn/ui + next-themes           |
| better-auth client      | custom: middleware reads `om_access`/`om_refresh` cookies, server components forward them to the gateway |

Dev port is 5173 (gateway keeps :3000). Dev server: `make frontend`.
