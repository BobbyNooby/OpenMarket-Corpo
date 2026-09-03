# Gateway configuration

> [gateway](../README.md) › Configuration

Every env var, its default, and what breaks without it. All defaults are
chosen so `make gateway` works against `make auth` with zero configuration.

## Env vars

| Var | Required | Default | Meaning |
|---|---|---|---|
| `PORT` | no | `3000` | Listen port |
| `AUTH_URL` | no | `http://localhost:8080` | Auth REST base — the proxy target for `/api/v1/auth`, `/api/v1/users`, `/api/v1/admin`, and jwks |
| `AUTH_GRPC_URL` | no | `localhost:9090` | Auth gRPC (`IntrospectToken`) — the edge-auth channel |
| `GRPC_INTERNAL_SECRET` | no | `dev-internal-secret` | Shared secret for the gRPC hop; **must match** auth's value. Mismatch = every protected route 503s |
| `CATALOGUE_URL` | no | `http://localhost:8081` | Catalogue REST base — the live proxy target for `/api/v1/catalogue` |
| `MESSAGING_URL` … `ADMIN_URL` | no | `localhost:8082`–`8085` | Pending services — used only by `/health/system` |

The missing-var failure mode is always "wrong target", never a crash: a bad
`AUTH_URL` surfaces as 502s on auth routes; a bad `AUTH_GRPC_URL` surfaces
as 503s on protected routes. Check `/health/system` first — it reports the
REST and gRPC channels separately.

## Compose wiring

The compose file sets everything explicitly:

- Fixed internal subnet `10.200.200.0/24`, gateway pinned at `10.200.200.10`
  — auth trusts X-Forwarded-For from exactly that `/32`.
- `GRPC_INTERNAL_SECRET: ${GRPC_INTERNAL_SECRET:-dev-internal-secret}` on
  **both** gateway and auth — set a real value in the shell env for anything
  non-local.
- `AUTH_GRPC_URL: auth:9090`; auth's 9090 is unpublished.
- Gateway healthcheck polls `/health/live`; `depends_on: auth
  (service_healthy)` orders startup but the gateway tolerates auth being
  down after that.

## Per-environment notes

- **Local dev**: everything defaults. The one quirk: auth's cookies are
  Secure-by-default and Safari refuses Secure cookies on `http://localhost`
  — use Chrome/Firefox, or set `AUTH_COOKIE_SECURE=false` for the auth
  container.
- **Compose (local stack)**: works as checked in; optionally export
  `GRPC_INTERNAL_SECRET` and `AUTH_COOKIE_SECURE`.
- **Staging/prod**: real `GRPC_INTERNAL_SECRET` (not the dev default),
  `AUTH_COOKIE_SECURE=true`, and TLS terminating in front of the gateway.
