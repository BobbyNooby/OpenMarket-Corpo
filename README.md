# openmarket-corpo

Polyglot microservices rebuild of the OpenMarket marketplace concept.

- **Repo:** `openmarket-corpo`
- **Vault docs:** `projects/openmarket-v2/` (concept, roadmap, workplan, README)

## Stack

| Service | Lang / Stack | DB |
|---------|-------------|-----|
| Gateway / BFF | Go | — |
| Auth & Users | Java Spring Boot | `auth_db` |
| Catalogue & Listings | C# ASP.NET Core | `catalogue_db` |
| Messaging | Go | `messaging_db` |
| Presence & Notifications | Python FastAPI | `notif_db` |
| Assets & Images | Python FastAPI | `asset_db` |
| Admin & Moderation | Java Spring Boot | `admin_db` |

Infra: Kafka (KRaft), Redis, PostgreSQL 17 (one instance, 6 DBs), OpenTelemetry / Jaeger / Prometheus / Grafana.

## Quick start

```bash
make infra-up      # postgres, kafka, redis, jaeger
make build         # build all 7 services
make up            # everything running
```

Or directly:
```bash
docker compose -f deploy/compose/docker-compose.yml up --build
```

Gateway: `http://localhost:3000`
Jaeger UI: `http://localhost:16686`
