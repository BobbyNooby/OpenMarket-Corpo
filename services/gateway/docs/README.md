# gateway — service documentation

Full documentation for the OpenMarket v2 **gateway** (API Gateway / BFF).
Start at [`../README.md`](../README.md) for the overview and quick start.

## Reading order

| # | Page | What it covers |
|---|------|----------------|
| 1 | [architecture.md](architecture.md) | Position in the fleet, trust model, request lifecycle, module map, transport decisions |
| 2 | [routing.md](routing.md) | Route table, per-service mount pattern, stub semantics, ServeMux pitfalls |
| 3 | [edge-auth.md](edge-auth.md) | The `IntrospectToken` gRPC, the decision matrix, verdict cache, ban-propagation window |
| 4 | [security.md](security.md) | Edge sanitization guarantees, secret guard, fail-closed posture, known gaps |
| 5 | [configuration.md](configuration.md) | Every env var, compose wiring, local dev matrix |
| 6 | [testing.md](testing.md) | The 17-test suite, the mutation gauntlet, flow-test §14, how to extend |
| 7 | [roadmap.md](roadmap.md) | What lands here next (WS, rate limiting, BFF aggregation, blocklist) and why it's deferred |

## Topic shortcuts

- *"Why did my request get a 501 / 404 / 502?"* → [routing.md](routing.md)
- *"Why is auth suddenly 503ing everything?"* → [edge-auth.md](edge-auth.md)
- *"How does the gateway know who I am?"* → [edge-auth.md](edge-auth.md)
- *"Can a client forge X-Forwarded-For?"* → [security.md](security.md)
- *"How do I add the catalogue service to the gateway?"* → [routing.md](routing.md#adding-a-service)
- *"Which env vars do I need?"* → [configuration.md](configuration.md)
- *"How do I know the tests actually test anything?"* → [testing.md](testing.md#the-mutation-gauntlet)

## Repo-level docs

- [`docs/ARCHITECTURE.md`](../../../docs/ARCHITECTURE.md) — fleet-wide decisions
- [`DEV.md`](../../../DEV.md) — repo-wide dev guide
- Sibling deep-dive: [`services/auth/docs/`](../../auth/docs/README.md) — the
  auth service, this gateway's first upstream
