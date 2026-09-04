# catalogue — service documentation

Full documentation for the OpenMarket v2 **catalogue** service. Start at
[`../README.md`](../README.md) for the overview and quick start.

## Reading order

| # | Page | What it covers |
|---|------|----------------|
| 1 | [architecture.md](architecture.md) | Position in the fleet, request path, data model, FCFS accept mechanics, endpoint inventory, configuration |
| 2 | [security.md](security.md) | Deny-by-default posture, ownership-in-query-predicate, identity derivation, fail-closed introspection, known gaps from the 2026-09 audit |
| 3 | [testing.md](testing.md) | The 31-test suite: layers, fixtures, what each test pins, the fault-injection session, deliberately-NOT-tested list |

## Topic shortcuts

- *"What happens when two buyers accept at once?"* →
  [architecture.md — the FCFS accept](architecture.md#the-fcfs-accept)
- *"What does error code X mean?"* →
  [architecture.md — endpoint inventory](architecture.md#endpoint-inventory) (every error code is listed next to the endpoint that returns it)
- *"Why did my mutation get a 503 when auth is down?"* →
  [security.md — fail-closed introspection](security.md#fail-closed-introspection)
- *"Does the outbox relay actually publish anything?"* →
  [architecture.md — events and the outbox](architecture.md#events-and-the-outbox)
  (spoiler: no relay — the rows accumulate; readiness degrades at 10k)
- *"What's still insecure?"* → [security.md](security.md#known-gaps)
- *"Which env vars do I need?"* →
  [architecture.md — configuration](architecture.md#configuration)
- *"Why does a retry return 200 instead of creating twice?"* →
  [architecture.md — idempotency](architecture.md#idempotency)

## Repo-level docs

- [`docs/ARCHITECTURE.md`](../../../docs/ARCHITECTURE.md) — fleet-wide decisions
- [`docs/AUDIT-2026-09-03.md`](../../../docs/AUDIT-2026-09-03.md) — the audit whose
  catalogue findings are tracked in [security.md](security.md) and [testing.md](testing.md)
- [`DEV.md`](../../../DEV.md) — repo-wide dev guide
