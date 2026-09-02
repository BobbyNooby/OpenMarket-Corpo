# Routing

> [gateway](../README.md) › Routing

## The route table

| Pattern | Upstream | Edge check | Originates |
|---|---|---|---|
| `/api/v1/auth/`, `/api/v1/users/`, `/api/v1/admin/` (+ bare `/api/v1/auth`) | auth `:8080` | yes | — |
| `/.well-known/jwks.json` | auth | public | — |
| `/api/v1/catalogue/` | stub | — | `501 not_deployed` |
| `/api/v1/messaging/` | stub | — | `501 not_deployed` |
| `/api/v1/presence/` | stub | — | `501 not_deployed` |
| `/api/v1/assets/` | stub | — | `501 not_deployed` |
| `/api/` and `/api` | — | — | `404 not_found` |
| `/health/live`, `/health/ready`, `/health/system` | gateway itself | public | 200 / 503 |
| `/` | — | — | 200 info JSON |

Status semantics for pending routes: **501** = "route exists, service not
deployed yet" · **404** = "no such route anywhere" · **502** = "deployed but
unreachable". A frontend can trust the difference.

## Adding a service

The pattern is one `Mount()` per upstream, registered in `main.go`:

1. Create `internal/upstream/<service>/mount.go` with
   `func Mount(mux *http.ServeMux, cfg Config)` that builds
   `proxy.New(target, logger)` and registers the service's prefixes.
2. Decide the edge policy: either wrap with `middleware.Auth` (protected
   surface) or mount raw (public). Mixed surfaces use the middleware's
   `IsPublic` exact-match list — never prefix rules.
3. Replace the stub registration in `main.go` with the real `Mount`.
4. Add the service's URL env var to the composition root and
   `/health/system`.

Catalogue is the first candidate: its API is REST today, so `Mount` is a
near-copy of auth's minus the middleware, plus CORS-free same-origin
proxying for free.

## ServeMux pitfalls (why the code looks the way it does)

- **Trailing slashes**: the Go 1.22+ mux 301-redirects `/api/v1/auth/login/`
  to the registered `/api/v1/auth/login` — which rewrites a POST into a GET.
  This is why the edge middleware classifies paths itself (exact-match on
  the cleaned `r.URL.Path`) instead of leaning on mux patterns, and why
  public matching must not use prefix rules (`/api/v1/auth/login` must not
  make `/api/v1/auth/login/resend` public).
- **Precedence**: longest pattern wins, so the explicit `/api/` catch-all
  never shadows a registered service mount, and the root `/` handler never
  swallows unknown API routes.
- **Bare prefixes**: `/api/v1/auth` (no trailing slash) is registered
  explicitly — ServeMux would otherwise not match it, and an upstream 404 is
  more truthful than a gateway default.
- **Encoding**: classification uses the decoded path; forwarding preserves
  the escaped path. Mismatches can only misclassify in the safe direction —
  auth re-validates everything the edge lets through.
