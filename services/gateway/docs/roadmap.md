# Gateway roadmap

> [gateway](../README.md) › Roadmap

## Shipped

- [x] **Skeleton + per-service mounts** — auth live (3 route families +
  jwks), stubs answering `501 not_deployed` for the pending services
  (messaging/presence/assets — catalogue has since been mounted live),
  unknown `/api/*` → 404
- [x] **Catalogue upstream** — second live mount (plain REST proxy; catalogue
  enforces its own authn + ban checks upstream)
- [x] **Edge authentication** — `IntrospectToken` over gRPC, fail-closed,
  verdict cache (10s TTL / 10k cap), internal-secret guard
- [x] **Edge sanitization** — XFF overwrite (stdlib + explicit), identity
  and forwarding headers stripped, body cap, timeouts
- [x] **Observability floor** — `/health/system` with REST + gRPC channel
  detail, JSON logs without tokens
- [x] **Tests** — 17 unit tests + flow-test §14, mutation-verified

## Deliberately deferred

| Item | Why / when it arrives |
|---|---|
| **WebSocket termination** (presence) | `FlushInterval: -1` + upgrade passthrough are already in the proxy factory; the WS fan-out protocol lands with presence (Phase 2). Auth at upgrade time only — cookie introspected once, not per frame. |
| **Fleet rate limiting + idempotency keys** (Redis) | auth throttles its own sensitive endpoints today; fleet-wide limiting needs the Redis cache layer and per-route budgets (Phase 4 infra fast-follow) |
| **JWT blocklist consumption** (Redis) | the design's answer to the ≤15-min access-token window after a ban; arrives with the gateway's Redis wiring — until then the edge introspection cache (≤10s) plus auth's login/refresh checks cover it |
| **mTLS on the auth gRPC hop** | plaintext h2c + shared secret + unpublished port is the staged posture; mTLS arrives with K8s/mesh (Phase 5) |
| **Catalogue/Admin over gRPC** | both still REST-proxied; each endpoint migrates per the hybrid-transport path in [architecture.md](architecture.md#transport-decisions-and-the-one-honest-deviation) |
| **BFF DTO aggregation** (`/api/v1/me` joining auth + reputation) | depends on `GetUser` RPC (declared, UNIMPLEMENTED) and the reputation domain — the proto comment reserves the seam |
| **buf-based protobuf codegen** | `contracts/generate.sh` + committed `.pb.go` work today with zero CI tooling; buf adds lint/breaking-change checks when the contract count grows past a handful |
| **`/health/system` parallelization** | sequential 2s-timeout probes are fine at six services; parallelize + cache if monitors hit it aggressively |
| **`GetIdentity` production caller** | scaffolding for BFF aggregation — handlers will read identity from context once DTO shaping starts |

## Known bounded windows

- **Ban propagation ≤ 10s** through the gateway (verdict cache TTL);
  enforced outright at login/refresh. Pinned by flow-test §14.
- **Swagger/actuator not proxied** — internal tooling; must stay that way
  unless prod-profile gating lands first (see auth security.md).
