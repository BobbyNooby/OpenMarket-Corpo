# messaging — Messaging (chat)

The **messaging** service owns conversations and chat between OpenMarket users.

- **Stack:** Go (net/http), port 8082
- **Database:** PostgreSQL (`messaging_db`)
- **Cache / Pub-Sub:** Redis (typing indicators, unread tracking, per-conversation WS fan-out)

## Current state

- Basic Go skeleton: `/`, `/health/live`, `/health/ready`.
- `/health/ready` pings the Postgres database and reports `ready` / failure.

## What this service will do

- Create and manage conversations
- Send and retrieve messages
- Typing indicators + unread counts
- Per-conversation WebSocket fan-in/out
- Publish `message.created` / `message.deleted` events to Kafka

## Local dev

```bash
# from repo root
make postgres
make messaging
# or: cd services/messaging && go run .
```

Verify:

```bash
curl http://localhost:8082/
curl http://localhost:8082/health/live
curl http://localhost:8082/health/ready
```

Requires `DATABASE_URL` (defaults to `messaging_db`).