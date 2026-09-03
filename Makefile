.PHONY: infra-up build up down clean test lint \
       postgres auth catalogue messaging presence assets admin gateway \
       frontend infra-down

COMPOSE := docker compose -f deploy/compose/docker-compose.yml

# ─────────────────────── INFRA ───────────────────────

infra-up:
	$(COMPOSE) up -d postgres kafka redis jaeger
	@echo "Waiting for infra to be healthy..."
	$(COMPOSE) up -d postgres kafka redis jaeger --wait
	@echo "Infra ready."

infra-down:
	$(COMPOSE) down

# Just postgres for local dev
postgres:
	$(COMPOSE) up -d postgres
	@echo "Waiting for postgres..."
	$(COMPOSE) up -d postgres --wait
	@echo "Postgres ready on localhost:$${POSTGRES_HOST_PORT:-5432} (compose reads deploy/compose/.env)"

# ─────────────────────── DOCKER (all services) ───────────────────────

build:
	$(COMPOSE) build

up:
	$(COMPOSE) up -d

down:
	$(COMPOSE) down

clean:
	$(COMPOSE) down -v --remove-orphans
	@echo "Volumes removed. Run 'make infra-up' to start fresh."

# ─────────────────────── LOCAL DEV (individual services) ───────────────────────
# Run from repo root: make auth, make gateway, etc.
# Each starts a service locally (not in Docker).

auth:
	cd services/auth && mvn spring-boot:run

admin:
	cd services/admin && mvn spring-boot:run

catalogue:
	cd services/catalogue && dotnet run

gateway:
	cd services/gateway && go run .

messaging:
	cd services/messaging && go run .

presence:
	cd services/presence && uvicorn main:app --reload --port 8083

assets:
	cd services/assets && uvicorn main:app --reload --port 8084

frontend:
	cd frontend && npm run dev

# ─────────────────────── TEST / LINT ───────────────────────

test:
	@echo "=== Go tests (gateway + messaging) ==="
	cd services/gateway && go test ./...
	cd services/messaging && go test ./...
	@echo "=== Python tests (presence + assets) ==="
	cd services/presence && python3 -m pytest
	cd services/assets && python3 -m pytest
	@echo "=== Java tests (auth) ==="
	cd services/auth && mvn -q test
	@echo "=== dotnet tests (catalogue) ==="
	cd services/catalogue && dotnet test
	@echo "All tests passed."

lint:
	@echo "=== Go lint (gateway + messaging) ==="
	cd services/gateway && go vet ./...
	cd services/messaging && go vet ./...
	@echo "=== Python lint (presence + assets) ==="
	cd services/presence && ruff check .
	cd services/assets && ruff check .
	@echo "=== Java compile (auth) ==="
	cd services/auth && mvn -q compile
	@echo "=== dotnet build (catalogue) ==="
	cd services/catalogue && dotnet build
	@echo "All lint passed."
