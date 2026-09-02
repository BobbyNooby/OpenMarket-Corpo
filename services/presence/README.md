# presence — Presence & Notifications

The **presence** service owns online status, real-time fan-out, and
notifications for OpenMarket users.

- **Stack:** Python / FastAPI, port 8083
- **Database:** PostgreSQL (`notif_db`)
- **Cache / Pub-Sub:** Redis (online status, fan-out)

## Current state

- Basic FastAPI skeleton: `/`, `/health/live`, `/health/ready`.

## Planned responsibilities

- Online status (WS presence heartbeats, Redis-published)
- WebSocket fan-out for notifications (per-user channels)
- 7 notification types (offers, messages, reviews, follows, …)
- Consumes `message.created` and other Kafka events for notification triggers

The gateway terminates the user-facing WebSocket and forwards to this
service — see the gateway's [roadmap](../gateway/docs/roadmap.md) for the
WS-termination deferral.

## Local dev

```bash
# from repo root (needs infra: make infra-up)
make presence
```

Tests: `python3 -m pytest` (health smoke tests in `tests/`).
