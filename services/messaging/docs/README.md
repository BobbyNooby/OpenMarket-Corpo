# messaging — service documentation

Full documentation for the OpenMarket v2 **messaging** (chat) service. Start at
[`../README.md`](../README.md) for the overview and quick start.

## Reading order

| # | Page | What it covers |
|---|------|----------------|
| 1 | [architecture.md](architecture.md) | Position in the fleet, two-layer trust model, request path, data model, endpoint + config inventory, deployment shape |
| 2 | [security.md](security.md) | Identity chain, participant scoping + 404 masking, CSWSH defence, content limits, known gaps |
| 3 | [testing.md](testing.md) | The 20-test suite: three layers, what each test pins, what's deliberately not tested |

## Topic shortcuts

- *"What happens when a non-participant probes a conversation id?"* → [security.md](security.md#participant-scoping-and-404-masking) — indistinguishable from an unknown conversation (`store.go:12-15`, `handlers.go:244`)
- *"Why does my websocket get refused with 403?"* → [architecture.md](architecture.md#websocket-push-channel) — the Origin header isn't on the `WS_ALLOWED_ORIGINS` allowlist (`handlers.go:50-56`); an empty Origin (native clients, tests) is allowed
- *"What happens when a user opens a 9th tab?"* → [architecture.md](architecture.md#websocket-push-channel) — the new socket is refused with close code 1013 `CloseTryAgainLater` (`hub.go:15`, `handlers.go:369-376`)
- *"Why did pair-create ever fork into two conversations?"* → [architecture.md](architecture.md#data-model) — the advisory-lock race fix (`store.go:79-88`)
- *"Why does my message show as read when I sent it?"* → sending counts as reading your own thread (`store.go:271-276`)
- *"What's still untested?"* → [testing.md](testing.md#deliberately-not-tested-yet) — the PostgresStore SQL is the top debt
- *"Which env vars do I need?"* → [architecture.md](architecture.md#configuration)

## Repo-level docs

- [`docs/ARCHITECTURE.md`](../../../docs/ARCHITECTURE.md) — fleet-wide decisions
- [`docs/AUDIT-2026-09-03.md`](../../../docs/AUDIT-2026-09-03.md) — the audit that produced the hub cap, negative JWKS cache, and pair-create lock fixes
- [`contracts/openapi/messaging.v1.yaml`](../../../contracts/openapi/messaging.v1.yaml) — REST + WS push envelope contract
- [`DEV.md`](../../../DEV.md) — repo-wide dev guide
