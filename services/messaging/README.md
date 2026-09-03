# messaging — Messaging (chat)

The **messaging** service owns conversations and chat between OpenMarket users.

- **Stack:** Go (net/http + gorilla/websocket), port 8082
- **Database:** PostgreSQL (`messaging_db`) — schema applied by the service's embedded migrations
- **Contract:** [`contracts/openapi/messaging.v1.yaml`](../../contracts/openapi/messaging.v1.yaml)
- **Tests:** [`docs/testing.md`](docs/testing.md) — 20 tests, three layers, and what's deliberately not tested

## Current state — LIVE (Phase 2, slice 1)

Ported from v1's chat domain:

- `POST /api/v1/messaging/conversations` — create (or idempotently return) the 1:1
  conversation with another user, optionally anchored to a listing (`listingId` is a
  plain uuid pointer — catalogue owns listings, no cross-service FK)
- `GET  /api/v1/messaging/conversations` — mine, newest activity first, with last
  message + per-conversation unread count
- `GET  /api/v1/messaging/conversations/unread-count`
- `GET  /api/v1/messaging/conversations/{id}/messages` — cursor pagination
  (`before` + `limit` 1..100, default 50); participants only, 404-masked otherwise
- `POST /api/v1/messaging/conversations/{id}/messages` — content ≤ 4000 chars,
  trimmed; bumps `updated_at`, sending counts as reading your own thread
- `POST /api/v1/messaging/conversations/{id}/read` — mark read up to now
- `DELETE /api/v1/messaging/messages/{id}` — soft delete, sender only (403 for others)
- `GET /ws` — authenticated server→client push channel; REST is the only write path

### Identity & auth

Every request (and the WS upgrade) must present a token the service can verify:
RS256 JWT via `Authorization: Bearer` or the `om_access` cookie, validated against
**auth's JWKS** (`AUTH_URL/.well-known/jwks.json`, cached, refreshed on unknown
`kid`). The gateway additionally edge-checks every messaging route (introspection +
ban blocklist) — the service-level validation means the chat service stays safe even
if reached directly on the internal network.

The WS upgrade enforces an explicit Origin allowlist (`WS_ALLOWED_ORIGINS`) — the
socket rides the session cookie, and an unchecked Origin would be a cross-site
WebSocket hijack.

### Deliberately not here yet

- Kafka events (`message.created` …): deferred until presence consumes them —
  no producers without consumers
- typing indicators, edited messages, group conversations
- Redis fan-out (single-instance in-memory hub today; horizontal scale is an
  events/Redis phase decision)

## Local dev

```bash
# from repo root
make postgres
make messaging
# or: cd services/messaging && go run .
```

Verify:

```bash
curl http://localhost:8082/health/live
curl http://localhost:8082/health/ready   # pings Postgres
```

Requires `DATABASE_URL` (messaging_db) and `AUTH_URL` for JWT validation.
