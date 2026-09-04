# Code Organization & Test-Structure Audit

**Date:** 2026-09-04 · **Scope:** all services — file/package layout, test
organization, documentation coverage · **Trigger:** owner review of the
messaging slice ("why is everything in one long file / one test file?")

The short answer to that question: the messaging slice was written flat
because it was fast, not because it's right. This audit maps every service
against the fleet's own best examples and proposes the concrete restructure.

## The fleet's own reference points

| Service | Production layout | Test layout | Docs |
|---|---|---|---|
| **auth (Java)** | package-by-feature (`auth/`, `token/`, `oauth/`, `admin/`, `user/`, `grpc/`, `common/`, `events/`) | 23 test classes, one per feature area, each named for what it pins | **10-page docs tree** with reading order (docs/README.md) — the model |
| **gateway (Go)** | `internal/` packages with single responsibilities (`middleware`, `proxy`, `blocklist`, `httpx`, `upstream/*`), interfaces at the seams, thin `main.go` composition root | one test file per package, next to the code | 7-page docs tree incl. a testing doc with a mutation gauntlet |
| **catalogue (C#)** | folder-by-concern, done right: `Domain/`, `Endpoints/` (one file per endpoint family), `Infrastructure/`, `Auth/`, `AuthGrpc/` | **28 of 31 tests in one `SecurityAndFlowTests.cs`** — the grab-bag problem | docs tree added 2026-09-04 |
| **messaging (Go)** | **flat `package main`, six files, mixed concerns** — `handlers.go` holds routing + validation + envelopes + the WS read pump | **12 endpoint tests in one `handlers_test.go`** | docs tree added 2026-09-04; testing doc already per-layer |
| admin / presence / assets | 1–4 files each — fine *at skeleton size*, will need the same treatment when domains land | smoke only | README only |

## Findings

### F1 — messaging's flat layout is past its size (the trigger)

`store.go`, `handlers.go`, `hub.go`, `jwt.go`, `migrate.go`, `main.go` all
in `package main` means: nothing enforces boundaries, `handlers.go`
(#~390 lines) mixes HTTP concerns with the WS read pump, and the tests
can't sit next to the package they exercise because there is one package.
The gateway proves the codebase already knows better.

### F2 — "the singular test file" exists in two services

- messaging `handlers_test.go`: 12 tests covering every endpoint's authz,
  validation, masking, and pagination in one file.
- catalogue `SecurityAndFlowTests.cs`: 28 integration tests (authz, FCFS
  races, idempotency, expiry, health) in one file.

Both are discoverability problems: a newcomer asking "where are the trade
accept tests?" gets "somewhere in the 700-line file".

### F3 — catalogue's events are dead letters (found during the docs pass)

Catalogue writes outbox rows for listing/trade/expiry events
(`ListingEndpoints.cs:167,362`, `ExpiryScanner.cs:75`) but has **no
relay** — unlike auth, which got one on 2026-09-03. Its
`/health/ready` already answers 503 `degraded` above 10k unpublished rows
(`Program.cs:123-143`) — the service is honest about the backlog, but the
backlog grows forever. Top follow-up: port auth's `OutboxRelay` pattern.

### F4 — compose passes env vars nothing consumes

`KAFKA_BROKERS` and `OTLP_ENDPOINT` are read nowhere in catalogue source
(grep-verified during the docs pass). Messaging's unused envs were removed
on 2026-09-03; catalogue's are still decorative. Either wire OpenTelemetry
or delete the vars — the compose file should not lie.

### F5 — docs coverage is now uniform where it matters

auth (10 pages), gateway (7), catalogue (4, new), messaging (3+testing,
new), frontend (README + testing, new). admin/presence/assets are
skeletons with honest READMEs — docs trees grow when the domains do.

## Proposed restructure (messaging) — for a GO in a separate session

Mirror the gateway. Pure moves, zero behavior change, all 20 tests green
after, `go vet` + `-race` clean:

```
services/messaging/
├── main.go                    # composition root ONLY: env, dial, migrate, wire, serve
└── internal/
    ├── store/                 # store.go (Store interface, PostgresStore), migrate.go
    │   └── store_test.go      # (future) Testcontainers integration — the top test debt
    ├── chat/                  # app wiring, envelope, handlers split per area:
    │   ├── conversations.go   #   create/list/unread/read
    │   ├── messages.go        #   list/send/delete + broadcast hook
    │   ├── conversations_test.go
    │   └── messages_test.go
    ├── ws/                    # hub.go + upgrade handler + Origin allowlist
    │   ├── hub.go
    │   └── hub_test.go        # (from ws_test.go, split: push vs origin vs caps)
    └── authn/                 # Verifier interface, JWKSVerifier, token extraction
        ├── jwt.go
        └── jwt_test.go        # (from jwt_test.go verbatim)
```

Rules this encodes (fleet-wide from here on):

1. package-by-feature under `internal/`; `main.go` stays under ~80 lines
2. one test file per concern, next to the package it exercises
3. HTTP handlers split by endpoint family, not one god-file
4. the WS read pump lives with the hub, not with REST handlers

## Proposed catalogue test split (same session or next)

`SecurityAndFlowTests.cs` → `AuthorizationTests.cs` (ownership, masking,
ban gates), `TradeAcceptTests.cs` (FCFS, idempotency, replay, the race),
`ListingCrudTests.cs` (create/patch/expire/pagination), `HealthTests.cs`
(readiness/backpressure). Pure re-file, no logic change; `dotnet test`
green after.

## Priority order

1. **Catalogue outbox relay** (F3) — port auth's `OutboxRelay`; until then
   every listing/trade event is a dead letter.
2. **messaging restructure** (F1+F2) — the plan above.
3. **catalogue test split** (F2).
4. **compose env honesty** (F4) — wire or delete `KAFKA_BROKERS`/`OTLP_ENDPOINT`.
5. admin/presence/assets: adopt the same layout rules when their domains land.
