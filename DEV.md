# Local Development Guide

## Prerequisites

- Docker Desktop running
- Java 21 (for Spring Boot services)
- Maven (for Spring Boot services)
- Go (for Go services)
- Python 3.12+ (for FastAPI services)
- .NET SDK (for C# service)
- Node 20.9+ (for the Next.js frontend; repo uses 22 — see `frontend/.nvmrc`)

## Quick Start

```bash
# 1. Start Postgres (required for all services)
make postgres

# 2. Run a service locally (pick one)
make gateway       # Go on :3000
make auth          # Spring Boot on :8080
make catalogue     # C# on :8081
make messaging     # Go on :8082
make presence      # Python FastAPI on :8083
make assets        # Python FastAPI on :8084
make admin         # Spring Boot on :8085
make frontend      # Next.js on :5173 (talks to the gateway on :3000)
```

## Architecture

```
┌─────────────────────────────────────────────────┐
│                   Docker                         │
│  ┌─────────┐  ┌───────┐  ┌───────┐  ┌────────┐ │
│  │ Postgres │  │ Kafka │  │ Redis │  │ Jaeger │ │
│  │  :5432   │  │ :9092 │  │ :6379 │  │ :16686 │ │
│  └─────────┘  └───────┘  └───────┘  └────────┘ │
└─────────────────────────────────────────────────┘
         ↑
         │ localhost:5432
    ┌────┴─────────────────────────────────┐
    │         Local Services                │
    │  gateway:3000  auth:8080  cat:8081    │
    │  msg:8082  presence:8083  assets:8084 │
    │  admin:8085                           │
    └──────────────────────────────────────┘
```

Each service listens on its own port so you can run several at once while
testing. The Go gateway's default fallback URLs already point at these ports.

## Common Env Vars

All services share these via `.env` at repo root:

| Var | Default | Description |
|-----|---------|-------------|
| `POSTGRES_USER` | `om` | DB username |
| `POSTGRES_PASSWORD` | `devpassword123` | DB password |
| `POSTGRES_HOST` | `localhost` | DB host |
| `POSTGRES_PORT` | `5432` | DB port |

Auth-specific vars (see `services/auth/README.md` for the full story):

| Var | Default | Description |
|-----|---------|-------------|
| `JWT_KEY_PATH` | `keys/jwt-rsa.jwk` | RS256 signing key; **auto-generated on first boot** (gitignored) |
| `AUTH_COOKIE_SECURE` | `false` | Set `true` behind HTTPS |
| `DATABASE_URL` | — | Optional libpq URL; auth prefers it when set (e.g. Supabase) |

Each service connects to its own database:
- `auth` → `auth_db`
- `catalogue` → `catalogue_db`
- `messaging` → `messaging_db`
- `presence` → `notif_db`
- `assets` → `asset_db`
- `admin` → `admin_db`

## Makefile Targets

```bash
# Infra
make postgres       # Start just Postgres
make infra-up       # Start all infra (Postgres, Kafka, Redis, Jaeger)
make infra-down     # Stop all infra

# Individual services (run locally)
make auth           # Spring Boot
make admin          # Spring Boot
make catalogue      # C# ASP.NET
make gateway        # Go
make messaging      # Go
make presence       # Python FastAPI
make assets         # Python FastAPI
make frontend       # Next.js (frontend/)

# Docker (all services)
make build          # Build all Docker images
make up             # Start everything in Docker
make down           # Stop everything
make clean          # Stop + remove volumes

# Quality
make test           # Run all tests
make lint           # Run all linters
```

## Dev Workflow

### Working on auth (Spring Boot)

```bash
# Terminal 1: Postgres
make postgres

# Terminal 2: Auth service
make auth
# or: cd services/auth && mvn spring-boot:run

# Test it
curl http://localhost:8080/
curl http://localhost:8080/actuator/health
```

### Working on gateway (Go)

```bash
# Terminal 1: Postgres
make postgres

# Terminal 2: Gateway
make gateway
# or: cd services/gateway && go run .

# Test it
curl http://localhost:3000/
```

### Working on presence (Python)

```bash
# Terminal 1: Postgres
make postgres

# Terminal 2: Presence
make presence
# or: cd services/presence && uvicorn main:app --reload

# Test it
curl http://localhost:8083/
```

## Database Access

Connect directly to Postgres:

```bash
psql -h localhost -p 5432 -U om -d auth_db
# password: devpassword123
```

Or use any DB client (TablePlus, DBeaver, pgAdmin):
- Host: `localhost`
- Port: `5432`
- User: `om`
- Password: `devpassword123`
- Database: `auth_db` (or any service DB)
