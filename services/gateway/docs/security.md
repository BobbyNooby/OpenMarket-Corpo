# Gateway security

> [gateway](../README.md) › Security

The gateway is the trust boundary: everything it forwards, it vouches for.
This page lists the guarantees, how each is pinned, and what is deliberately
deferred.

## Edge sanitization

`internal/proxy` rewrites every request before it leaves the gateway:

- **`X-Forwarded-For` is set, never appended** — the gateway deletes any
  client-supplied value and writes the real peer address. Two layers make
  spoofing impossible: Go's `ReverseProxy` strips inbound XFF *before*
  `Rewrite` runs (Go 1.20+ behavior, discovered during mutation testing),
  and the gateway then sets the peer itself. Auth's
  `AUTH_TRUSTED_PROXY_IP=10.200.200.10/32` therefore sees exactly one
  trusted hop: the pinned gateway container.
- **Identity headers are stripped** (`X-User-Id`, `X-Roles`, `X-Email`,
  `X-User-Roles`) — nothing behind the gateway trusts them today, and a
  client must not be able to plant them before a future service starts.
- **`X-Forwarded-Host` and `Forwarded` (RFC 7239) are stripped** — the
  gateway is the authority on forwarding semantics.
- `X-Forwarded-Proto` is set from the actual inbound connection.
- Hop-by-hop headers are handled by `ReverseProxy` defaults.

Every one of these is pinned: the proxy tests plant a spoofed XFF and
planted identity headers and assert they never arrive.

## The internal secret

The auth introspection endpoint is an unauthenticated-by-design oracle — its
guards are the unpublished network plus `x-internal-secret`
(`GRPC_INTERNAL_SECRET`), compared in constant time. Both sides must agree;
a mismatch surfaces as edge 503s. The default `dev-internal-secret` is for
local dev only — compose passes the env through, so non-local deployments
set a real value in one place and both containers pick it up.

## Fail-closed posture

| Failure | Behavior |
|---|---|
| Auth gRPC unreachable | protected routes → `503`; REST hops → `502`; health/stubs/404s keep serving |
| Introspection deadline (1s) | same 503 path — fast, no pileups |
| Upstream dead (REST) | `502 bad_gateway` envelope |
| Invalid token | `401` at the edge |

The gateway coming up without auth is **degradation, not an outage**: boot
polls auth health for 10s, logs a warning, and serves anyway.

## Resource bounds

- `ReadHeaderTimeout` 5s · `ReadTimeout` 60s · `IdleTimeout` 120s · no
  `WriteTimeout` (streaming mounts must live).
- Request bodies capped at 10 MB at the edge (`http.MaxBytesHandler`) —
  proxied auth endpoints are small JSON; floods die here, not upstream.
- gRPC side (auth): fixed 32-thread executor, 64 KiB inbound cap.
- Verdict cache hard-capped at 10k entries.

## Known gaps / deliberate deferrals

- **mTLS on the gRPC hop** — plaintext h2c on the unpublished internal
  network, secret-guarded. mTLS lands with the mesh/K8s work.
- **No gateway-side rate limiting yet** — auth throttles its own sensitive
  endpoints; fleet-wide limiting is a roadmap item (Redis-backed).
- **Redis blocklist (live, 2026-09)** — the gateway consumes auth's
  `user.banned/unbanned/deleted` events into Redis and 401s blocked subs
  before introspection; a Redis outage fails open into introspection
  (which remains the authority). See `internal/blocklist`.
- **WebSocket termination (live, 2026-09)** — `/ws` proxies to messaging
  behind the edge check; messaging enforces its own Origin allowlist on
  the upgrade (CSWSH guard). Fleet-wide rate limiting remains deferred.
- **Swagger/docs are not proxied** — internal tooling stays internal.
