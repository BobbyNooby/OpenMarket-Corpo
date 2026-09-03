# Gateway architecture

> [gateway](../README.md) › Architecture

## Position in the fleet

The gateway is the **only public entry point**. The browser/Next.js app talks
to `:3000` and nothing else; every backend service is reached through here.
It is a stateless BFF: no database, no writes, no opinions about domain data —
its job is transport, trust, and traffic shaping.

```
browser ──REST/WS──▶ gateway ──gRPC──▶ auth (edge check)
                        └──REST proxy─▶ auth (the actual call)
                        └──REST proxy─▶ catalogue (upstream-owned authn)
                        └──(501 stubs)─▶ messaging / presence / assets
```

## Trust model

- The gateway is the **trust boundary**. Client-supplied forwarding headers
  (`X-Forwarded-For`, `X-Forwarded-Host`, `Forwarded`) and identity headers
  (`X-User-Id`, `X-Roles`, …) are deleted or overwritten at the edge — see
  [security.md](security.md#edge-sanitization).
- The gateway **never mints identity headers**. Upstreams receive the
  original token and re-validate it themselves; the edge check is a fast no,
  never the only check.
- The gateway↔auth gRPC hop carries a shared internal secret
  (`GRPC_INTERNAL_SECRET`) and rides the unpublished compose network.
  Plaintext h2c for now — TLS/mTLS is a documented deferral, not an oversight
  (see [roadmap.md](roadmap.md)).

## Request lifecycle

1. `http.ServeMux` dispatches on the longest registered pattern.
2. Auth route families pass through `middleware.Auth` first:
   public exact-match paths bypass; everything else is edge-authenticated
   (see [edge-auth.md](edge-auth.md#decision-matrix)).
3. `httputil.ReverseProxy` (from `internal/proxy.New`) forwards the request:
   sanitized headers, real peer in XFF, streaming-friendly flush, uniform
   502 envelope if the upstream is dead.
4. Unknown `/api/*` paths answer `404 not_found`; pending-service prefixes
   answer `501 not_deployed`; everything else gets the root info JSON.

## Module map

```
main.go                      composition root: env → dial gRPC → mount → serve
                             (graceful shutdown, shallow /health/ready,
                              /health/system with per-service + gRPC detail)
internal/proxy/              shared ReverseProxy factory (the edge guarantees)
internal/middleware/         edge authentication + verdict cache
internal/upstream/auth/      Mount(): auth's three route families + jwks
internal/upstream/catalogue/ Mount(): catalogue proxy (no edge middleware —
                             catalogue does its own JWT + ban checks)
internal/upstream/stub/      NotDeployed(service): the 501 placeholder
internal/authclient/         gRPC channel: dial, health poll, secret credential
internal/authpb/             generated stubs (committed — CI needs no protoc)
internal/httpx/              the {code, message} JSON envelope
```

## Transport decisions (and the one honest deviation)

The fleet decision is "gateway → services over internal gRPC from the start,
no REST-proxy stage". Shipped reality is deliberately hybrid:

- **gRPC, live**: `IntrospectToken` — the load-bearing edge check. Chosen
  first because it is small, well-typed, and immediately useful.
- **REST reverse-proxy, live**: the rest of auth's surface. Transcoding every
  endpoint to gRPC in one step was not worth the risk; endpoints migrate
  incrementally, and the proxy keeps the frontend working throughout.

The migration path is per-endpoint: add the RPC to
`contracts/proto/openmarket/auth/v1/auth.proto`, implement it in auth, switch
the gateway's call site, delete the REST route when the frontend stops using
it. Documented in the root README's "how services talk" table.

## Deployment shape

- Published port: `3000` only. Everything else is compose-internal.
- Non-root container (`gateway` user, uid 1500), curl present for the
  healthcheck.
- Joins the fixed `10.200.200.0/24` network at `.10`; auth trusts
  X-Forwarded-For from exactly that address.
- `depends_on: auth (service_healthy)` + own healthcheck — restarts are
  ordered but the gateway degrades gracefully either way.
