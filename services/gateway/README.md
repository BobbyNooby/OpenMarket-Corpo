# gateway — API Gateway / BFF

The **gateway** is the only public entry point for OpenMarket. The frontend only
ever talks to this service.

- **Stack:** Go (net/http), port 3000
- **State:** stateless; no database

## Current state

- Basic Go skeleton: `/`, `/health/live`, `/health/ready`.
- `/health/system` pings all 6 backend services and reports their readiness.

## What this service will do

- Route requests to auth, catalogue, messaging, presence, assets, admin
- Terminate WebSockets and fan out events
- Validate JWT sessions (signature check, Redis blocklist)
- Rate limiting and idempotency for mutations
- Shape per-service data into UI-ready DTOs (BFF pattern)

## Local dev

```bash
# from repo root
make gateway
# or: cd services/gateway && go run .
```

Verify:

```bash
curl http://localhost:3000/
curl http://localhost:3000/health/live
curl http://localhost:3000/health/system
```

Other services are located via env vars (`AUTH_URL`, `CATALOGUE_URL`, etc.)
with localhost fallbacks.