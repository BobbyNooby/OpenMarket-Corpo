# Local Development Guide

## Prerequisites

- Docker Desktop running
- Java 21 (for Spring Boot services)
- Maven (for Spring Boot services)
- Go (for Go services)
- Python 3.12+ (for FastAPI services)
- .NET SDK (for C# service)

## Quick Start

```bash
# 1. Start Postgres (required for all services)
make postgres

# 2. Run a service locally (pick one)
make auth          # Spring Boot on :8080
make gateway       # Go on :3000
make presence      # Python on :8080
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
    │  auth :8080  gateway :3000  etc.      │
    └──────────────────────────────────────┘
```

## Common Env Vars

All services share these via `.env` at repo root:

| Var | Default | Description |
|-----|---------|-------------|
| `POSTGRES_USER` | `om` | DB username |
| `POSTGRES_PASSWORD` | `devpassword123` | DB password |
| `POSTGRES_HOST` | `localhost` | DB host |
| `POSTGRES_PORT` | `5432` | DB port |

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
curl http://localhost:8080/
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
