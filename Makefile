.PHONY: infra-up build up down clean test lint

COMPOSE := docker compose -f deploy/compose/docker-compose.yml

infra-up:
	$(COMPOSE) up -d postgres kafka redis jaeger
	@echo "Waiting for infra to be healthy..."
	$(COMPOSE) up -d postgres kafka redis jaeger --wait
	@echo "Infra ready."

build:
	$(COMPOSE) build

up:
	$(COMPOSE) up -d

down:
	$(COMPOSE) down

clean:
	$(COMPOSE) down -v --remove-orphans
	@echo "Volumes removed. Run 'make infra-up' to start fresh."

test:
	@echo "=== Go tests (gateway + messaging) ==="
	cd services/gateway && go test ./...
	cd services/messaging && go test ./...
	@echo "=== Python tests (presence + assets) ==="
	cd services/presence && python -m pytest
	cd services/assets && python -m pytest
	@echo "All tests passed."

lint:
	@echo "=== Go lint (gateway + messaging) ==="
	cd services/gateway && go vet ./...
	cd services/messaging && go vet ./...
	@echo "=== Python lint (presence + assets) ==="
	cd services/presence && ruff check .
	cd services/assets && ruff check .
	@echo "All lint passed."
