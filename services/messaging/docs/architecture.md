# Architecture

> [messaging](../README.md) › Architecture

How the chat service fits into the OpenMarket v2 fleet, where trust lives,
and what it deliberately does not do yet.

## Contents

- [Position in the fleet](#position-in-the-fleet)
- [Trust model: two independent JWT checks](#trust-model-two-independent-jwt-checks)
- [Request path](#request-path)
- [Data model](#data-model)
- [WebSocket push channel](#websocket-push-channel)
- [Endpoint inventory](#endpoint-inventory)
- [Configuration](#configuration)
- [Deployment shape](#deployment-shape)
- [Deliberately deferred](#deliberately-deferred)

---

## Position in the fleet

Messaging is one of the live services in the polyglot v2 rebuild. It owns
conversations, messages, unread tracking, and the server→client push
channel — nothing else. All writes ride REST; the socket only fans out
what the store already committed (`main.go:1-3`).

```
   Browser ──▶ Go gateway (:3000) ──▶ messaging :8082 ──▶ messaging_db (Postgres)
                  │ edge check: ban blocklist +      │ validates JWT itself
                  │ introspection on every route     │ against auth's JWKS
                  ▼
                auth :8080  (JWKS only — messaging never calls auth per-request
                             for identity beyond the public key set)
```

- The gateway mounts `/api/v1/messaging/`, the bare `/api/v1/messaging`
  prefix (truthful upstream 404), and `/ws` — all wrapped in the edge
  auth middleware; messaging is **not** a public-by-default surface
  (`gateway/internal/upstream/messaging/mount.go:33-41`).
- What it will emit: a `message.created` Kafka event. Deferred until
  presence exists to consume it — no producers without consumers
  ([Deliberately deferred](#deliberately-deferred)).

## Trust model: two independent JWT checks

Every messaging request is checked twice, by different code with
different failure modes:

| Layer | Who | What it checks | Why both |
|---|---|---|---|
| Edge | gateway `middleware.Auth` | ban blocklist + token introspection on every messaging route and `/ws` (`mount.go:35-40`) | a banned user dies before the hop; shared proxy guarantees (XFF overwrite, identity-header stripping) hold for every mount |
| Service | messaging `JWKSVerifier` | RS256 signature + expiry against auth's JWKS, offline (`jwt.go:60-80`) | the service stays safe if reached directly on the internal network — it never trusts network position |

The service does not call auth to validate a token; it caches the
public key set (`AUTH_URL/.well-known/jwks.json`) and verifies locally,
same offline posture as the gateway's JWT check. See
[security.md](security.md#identity-chain) for the cache and rotation
rules.

## Request path

1. Browser sends `Authorization: Bearer <jwt>` or the `om_access` cookie
   to the gateway (same extraction rule on both sides — cookie fallback
   included, `handlers.go:83-92`).
2. Gateway edge middleware runs the blocklist + introspection check,
   then proxies to `MESSAGING_URL` (default `http://localhost:8082`,
   `gateway/main.go:112`).
3. Messaging resolves the caller itself: `extractToken` →
   `JWKSVerifier.Verify` → `sub` parsed as uuid (`handlers.go:69-81`,
   `jwt.go:60-80`). Failure is a uniform 401 `unauthorized` without
   distinguishing "missing" from "invalid".
4. Store queries are participant-scoped in SQL; a non-participant sees
   the same 404 as an unknown conversation.

## Data model

Three tables, ported from the v1 chat domain
(`~/Repositories/OpenMarket/packages/server/src/db/schemas.ts:294-336`),
owned end to end by embedded migrations in `migrate.go:13-42` recorded
in `schema_migrations` (`migrate.go:46-82`). Full definitions:
`migrate.go:15-41`.

| Table | Key columns | Indexes |
|---|---|---|
| `conversations` | `id` uuid PK, `created_at`, `updated_at`, `listing_id` uuid nullable | `idx_conversations_updated (updated_at)` |
| `conversation_participants` | PK `(conversation_id, user_id)`, `joined_at`, `last_read_at` nullable | `idx_participants_user (user_id)`; FK → conversations `ON DELETE CASCADE` |
| `messages` | `id` uuid PK, `conversation_id` FK cascade, `sender_id`, `content`, `created_at`, `edited_at`, `is_deleted` (soft delete) | `idx_messages_conv_created (conversation_id, created_at)` |

Notable choices:

- **`listing_id` has no foreign key.** Catalogue owns listings; v1 had a
  FK with `on delete set null`, but database-per-service means the
  pointer is a plain uuid — a dangling id is fine and intentional
  (`migrate.go:10-12`, `store.go:26`).
- **Unread is derived, not stored as a counter**: `last_read_at` per
  participant; unread = messages after the pointer, excluding your own
  and deleted ones (`store.go:132-138`).
- **Sending counts as reading your own thread** — `CreateMessage`
  bumps `conversations.updated_at` and advances the sender's
  `last_read_at` in the same transaction (`store.go:267-276`).

### Pair-create idempotency (the audit race)

`CreateOrGetConversation` takes `pg_advisory_xact_lock` on
`hashtext` of the sorted user pair before the check-then-insert
(`store.go:85`). Why: the uniqueness that makes create idempotent is
the pair itself, and the `(conversation_id, user_id)` PK only guards
membership — two concurrent creates for the same pair would both pass
the existence check and fork the thread into two conversations. The
transaction-scoped lock serializes same-pair creation; a hash collision
merely serializes an unrelated pair for a moment (`store.go:79-84`).
Replays return the existing conversation with 200; a fresh create is
201 (`handlers.go:171-174`).

## WebSocket push channel

`GET /ws` upgrades an authenticated connection to a **push-only**
channel (`handlers.go:358-391`):

- **The client never sends application frames.** REST is the only write
  path; the read pump just renews the 60s read deadline on pongs and
  enforces a 512-byte read limit on principle (`handlers.go:379-390`).
- **Envelope:** `{"type":"message.created","message":{...}}` — pinned
  contract, snake-case keys (`hub.go:69-73`,
  `contracts/openapi/messaging.v1.yaml:13`). Only `message.created`
  exists today.
- **Origin allowlist (CSWSH guard):** the socket rides `om_access`, so
  a browser Origin must exactly match an entry in `WS_ALLOWED_ORIGINS`
  (trailing `/` trimmed) or the upgrade is refused 403. Empty Origin
  (native clients, tests) is allowed (`handlers.go:26-29`, `44-57`).
  SameSite=Lax on the cookie is the second layer — see
  [security.md](security.md#cross-site-websocket-hijacking).
- **8-socket cap per user** (`maxConnsPerUser = 8`, `hub.go:15`): the
  9th concurrent socket is refused with close code 1013
  `CloseTryAgainLater` ("too many connections") so a reconnect storm
  can't grow the fan-out (`handlers.go:369-376`, `hub.go:45-56`).
- **Async fan-out:** writes run on per-socket goroutines with a 5s
  write deadline; a stalled participant socket never stalls the
  sender's REST response. Dead sockets are dropped — the client
  reconnects, REST remains the source of truth (`hub.go:75-98`).

The hub is in-memory per-process: single-instance today. Horizontal
scale needs Redis fan-out — deferred (below).

## Endpoint inventory

All routes registered in `handlers.go:94-110`. Contract:
[`contracts/openapi/messaging.v1.yaml`](../../../contracts/openapi/messaging.v1.yaml).

| Method | Path | Auth | Success | Errors |
|---|---|---|---|---|
| POST | `/api/v1/messaging/conversations` | JWT | 201 created / 200 idempotent replay | 400 invalid uuid, self-conversation, bad body; 401; 500 |
| GET | `/api/v1/messaging/conversations` | JWT | 200 `{conversations: [...]}` newest activity first, with last message + per-conv unread | 401; 500 |
| GET | `/api/v1/messaging/conversations/unread-count` | JWT | 200 `{count: n}` | 401; 500 |
| GET | `/api/v1/messaging/conversations/{id}/messages` | JWT + participant | 200 `{messages: [...]}` oldest→newest; `before` cursor, `limit` 1..100 default 50 | 400 bad uuid/limit; 401; **404 non-participant == unknown**; 500 |
| POST | `/api/v1/messaging/conversations/{id}/messages` | JWT + participant | 201 message; body ≤ 4512 bytes, content trimmed, ≤ 4000 chars | 400 empty/over-long/bad uuid; 401; 404 masked; 500 |
| POST | `/api/v1/messaging/conversations/{id}/read` | JWT + participant | 204 | 400 bad uuid; 401; 404 masked; 500 |
| DELETE | `/api/v1/messaging/messages/{id}` | JWT, sender only | 204 soft delete | 400 bad uuid; 401; 403 not the sender; 404 unknown/already deleted; 500 |
| GET | `/ws` | JWT + Origin allowlist | 101 upgrade → push channel | 401; 403 bad Origin; close 1013 at socket cap |
| GET | `/health/live` | none | 200 `{"status":"ok"}` | — |
| GET | `/health/ready` | none | 200 `{"status":"ready"}` | 503 `{"status":"db unreachable"}` (2s ping) |

Error envelope everywhere: `{"code": ..., "message": ...}`
(`handlers.go:61-65`). The 403/404 split on delete is deliberate — see
[security.md](security.md#participant-scoping-and-404-masking).

## Configuration

| Env var | Required | Default | Behavior |
|---|---|---|---|
| `DATABASE_URL` | yes | — | exits at boot if missing or Postgres unreachable after a 5s ping (`main.go:40-44`, `62-67`) |
| `DATABASE_SSLMODE` | no | lib/pq default | appended as `sslmode=` to the URL; lib/pq defaults to `require` for non-localhost hosts, so container-to-container (no TLS) needs `disable` (`main.go:45-53`) |
| `AUTH_URL` | no | `http://localhost:8080` | JWKS fetched from `AUTH_URL/.well-known/jwks.json` (`main.go:74`) |
| `WS_ALLOWED_ORIGINS` | no | empty (no browser origins allowed) | comma-separated exact Origin allowlist for `/ws` (`main.go:76`, `handlers.go:33-37`) |
| `PORT` | no | `8082` | listen address (`main.go:79`) |

Pool: max 10 open / 5 idle conns, 30min lifetime, shared by requests
and the readiness probe (`main.go:59-61`, `handlers.go:119-131`).

## Deployment shape

- **Database:** Postgres `messaging_db`, owned end to end by the
  service; migrations run at boot before listening (`main.go:69-72`).
- **Docker:** multi-stage `golang:1.23-alpine` build → `alpine:3.20`
  runtime, non-root `appuser` (uid 1500), binary at `/app`, `EXPOSE
  8082`, `curl` + CA certs present for healthchecks (`Dockerfile`).
- **Stateful on one axis:** the in-memory hub. Postgres holds all
  durable state; a restart loses only live sockets, which reconnect.
- **HTTP server timeouts:** read header 5s, read 30s, idle 120s; no
  WriteTimeout because WS connections are long-lived by design
  (`main.go:78-85`).

## Deliberately deferred

| Item | Reason |
|---|---|
| Kafka events (`message.created`) | waits for presence as the first consumer — no producers without consumers (`../README.md`: "Deliberately not here yet") |
| Typing indicators | not ported from v1 scope; needs a client→server WS path, which the push-only design currently forbids |
| Message editing | v1 has `edited_at` (soft delete reuses it, `store.go:310`) but no edit endpoint exists |
| Group conversations | store and API are 1:1-shaped (single `otherUserId`, `other.user_id <> $1` join, `store.go:141`) |
| Redis fan-out | single-instance in-memory hub today; horizontal scale is an events/Redis phase decision |

---

Related: [security.md](security.md) · [testing.md](testing.md) ·
fleet-wide decisions in [`docs/ARCHITECTURE.md`](../../../docs/ARCHITECTURE.md)
