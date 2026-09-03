# Messaging testing

> [messaging](../README.md) › Testing

20 Go tests in one package. `go vet ./...` is part of the gate — run
`go test -count=1 ./... && go vet ./...` from `services/messaging`.

## The layers

| Layer | Where | What it proves |
|---|---|---|
| Handler contract | `handlers_test.go` (12, fake store + fake verifier) | the HTTP decision matrix: 401 without touching the verifier on missing tokens, 201/200 create vs idempotent replay, self-conversation 400, uuid validation, non-participant == unknown (masked 404), content trim + 4000-char cap, pagination bounds 1..100 (default 50), mark-read 204/404, delete 403 vs 404 split, unread count passthrough |
| WS integration | `ws_test.go` (5, real gorilla sockets over httptest) | message POST pushes `message.created` to every participant socket; non-participants get nothing; upgrade requires a valid token; disallowed Origin → 403 (CSWSH); the push envelope's JSON keys are a pinned contract |
| JWT verifier | `jwt_test.go` (3, real generated RSA keys + fake JWKS endpoint) | valid RS256 resolves to sub; forged key fails; hand-crafted HS256 alg-confusion fails; missing exp fails; key rotation picked up and retired keys stop verifying; garbage-kid floods cost at most one JWKS fetch per negative-cache window |

## Deliberately NOT tested (yet)

- **PostgresStore against a real Postgres** — handler tests run on a fake
  `Store`, so the participant-scoping SQL, the advisory-lock pair-create
  serialization, and the cursor query are unguarded at the persistence
  level. This is the top testing debt; plan: Testcontainers-style
  integration tests (mirroring catalogue's `CatalogueFixture`) guarded by
  an env check so CI's docker-less lanes still pass.
- WS through the gateway end-to-end with real introspection — covered
  manually by the live e2e (2026-09-03), not yet automated.
- Concurrency: the hub's async broadcast and socket cap have no
  race-pinning test (`go test -race` exercised only single-client paths).
