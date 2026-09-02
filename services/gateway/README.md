# gateway — API Gateway / BFF

The **gateway** is the only public entry point for OpenMarket. The frontend
only ever talks to this service.

- **Stack:** Go (stdlib net/http), port 3000 — no router dependency
- **State:** stateless; no database
- **Edge auth:** gRPC `IntrospectToken` against the auth service

## Routing map

| Prefix | Upstream | Edge check |
|---|---|---|
| `/api/v1/auth/*`, `/api/v1/users/*`, `/api/v1/admin/*` | auth (`:8080`) | middleware |
| `/.well-known/jwks.json` | auth | public |
| `/api/v1/catalogue/*` … `/assets/*` | *stub* | 501 `not_deployed` |
| unknown `/api/*` | — | 404 `not_found` |
| `/health/*` | gateway itself | public |

The five stubbed prefixes answer a stable `501 not_deployed` envelope so the
frontend can distinguish "route exists, service pending" from "unknown route".
Filling a stub in = one `Mount()` call in `main.go`.

## Edge authentication (the first protobuf)

Protected requests carry a token (Authorization header wins, `om_access`
cookie falls back — mirroring auth's own resolver). The middleware:

1. **public path** → pass through untouched (exact-match list mirroring
   auth's permitAll — no prefix leakage into subpaths)
2. **no token** → forward anyway; the upstream owns the 401 shape
3. **token + `active=false`** → 401 at the edge (`invalid, expired, banned,
   deleted`)
4. **introspection unavailable** → 503, fail-closed; never degrade into
   "allow"

`IntrospectToken` is intentionally more than a signature check: auth answers
from the **database**, so bans and soft-deletes kill a token at the edge
within one request instead of one access-token TTL. Auth still re-validates
every forwarded request — the edge check is the fast no, never the only check.

Regenerate the protobuf stubs with `contracts/generate.sh`; generated
`.pb.go` files are committed so CI needs no protoc.

## Edge proxy guarantees (`internal/proxy`)

- `X-Forwarded-For` is **overwritten** with the real peer — a client-planted
  XFF can never reach auth's trusted-proxy resolution (regression-tested)
- client-planted identity headers (`X-User-Id`, `X-Roles`, …) are stripped
- `X-Forwarded-Proto` is set; hop-by-hop headers handled by ReverseProxy
- `FlushInterval: -1` — streaming-safe for future WS/SSE mounts
- upstream failures surface as a `502 bad_gateway` envelope

## Env vars

| Var | Default | Meaning |
|---|---|---|
| `PORT` | `3000` | listen port |
| `AUTH_URL` | `http://localhost:8080` | auth REST base |
| `AUTH_GRPC_URL` | `localhost:9090` | auth gRPC (IntrospectToken) |
| `CATALOGUE_URL` … `ADMIN_URL` | localhost ports | pending services |

`/health/ready` is deliberately shallow (process-up only) — auth being down
degrades protected routes to 503 but must not flap container readiness.
`/health/system` reports per-service status *including* the auth gRPC channel.

## Local dev

```bash
make gateway      # from repo root; or: cd services/gateway && go run .
```

The gateway needs auth running (REST :8080 + gRPC :9090). Verify:

```bash
curl localhost:3000/health/system   # auth-grpc should be "healthy"
curl -i localhost:3000/api/v1/auth/login -X POST -H 'Content-Type: application/json' \
  -d '{"email":"x@y.z","password":"nope"}'   # 401 envelope, proxied through
```
