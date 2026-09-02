# Gateway testing

> [gateway](../README.md) › Testing

17 Go tests across four packages, plus the gateway's slice of the fleet
flow-test (§14, 15 steps). `go vet ./...` is part of the gate — run
`go test ./... && go vet ./...` from `services/gateway`.

## The layers

| Layer | Where | What it proves |
|---|---|---|
| Middleware unit | `internal/middleware/auth_test.go` (8) | the decision matrix: public bypass, no-token forward, inactive → 401 edge, valid → identity in context + untouched header, cookie fallback, outage → 503 fail-closed, deadline bound, no subpath leakage |
| Verdict cache unit | `internal/middleware/cache_test.go` (3) | TTL expiry, hard cap, per-token isolation |
| Proxy unit | `internal/proxy/proxy_test.go` (3) | planted XFF never arrives upstream, planted identity headers stripped, dead upstream → 502 envelope |
| Routing integration | `internal/upstream/auth/auth_test.go` (2) | all three auth families + jwks proxy correctly, stubs cannot shadow real mounts, cache serves the second request |
| Stub contract | `internal/upstream/stub/stub_test.go` (1) | 501 envelope names the service |
| End-to-end | `services/auth/scripts/flow-test.sh` §14 (15 steps) | the real chain: register → login → me → refresh through `:3000`, edge 401 on forged tokens, banned user's pre-ban token dies at the edge, XFF spoof resistance, stub/404 semantics |

## The mutation gauntlet

Tests are only real if they can fail. Every security test above was
mutation-verified: the guarded production line was deliberately broken and
the test had to go red.

| Mutation | Killed by |
|---|---|
| XFF `Set` → `Add` (append) | proxy spoof test — and it revealed Go's `ReverseProxy` strips inbound XFF before `Rewrite` even runs |
| Identity-header strip removed | proxy test |
| Inactive token forwarded (no 401) | middleware invalid-token test |
| Fail-open on introspection error | middleware 503 test |
| Deadline removed | middleware timeout test |
| Cache `put` disabled | routing test (introspection count 1 → 2) |
| Cache expiry ignored | cache TTL test |
| Ban check removed (introspection) | introspection unit test |
| Soft-delete filter removed | introspection unit test — *with a meaningful stub*; the naive version passes vacuously because the test stubs the mutated method name (see below) |
| Garbage token → `active=true` | introspection garbage + expired tests |
| Discord ban check removed | auth service Discord tests |
| Throttle counts successes (old design) | auth lockout test |
| Edge middleware removed entirely | **flow-test §14 ban-propagation step** — this step exists because the gauntlet proved nothing else could detect it |
| Stub 501 → 404 | flow-test §14 stub step |

## The mock-name coupling caveat

Unit tests stub repository methods by name (`findByIdAndDeletedAtIsNull`).
If production regresses by *switching to a different method* (e.g. plain
`findById`), the stub stops matching and Mockito's default (empty) can
accidentally produce the same observable outcome — the mutation passes
vacuously. Detected during the gauntlet; the mitigation is the E2E layer,
which runs against a real database and real Hibernate semantics. Rule of
thumb: any change to *which repository method* a service calls deserves a
flow-test scenario, not just a unit test.

## Adding a test

1. Match the package style: table-driven where it helps, `t.Fatalf` with the
   expectation in the message, fakes over mocks where an interface exists
   (`middleware.Introspector`).
2. Security assertions name the exact status AND code
   (`jsonPath("$.code")`-style for Go: decode the envelope).
3. If you add a middleware/proxy behavior, add its mutation to this page —
   the gauntlet is a living list, and an un-mutated security test is a guess.
