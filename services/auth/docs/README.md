# auth — service documentation

Full documentation for the OpenMarket v2 **auth** service. Start at
[`../README.md`](../README.md) for the overview and quick start.

## Reading order

| # | Page | What it covers |
|---|------|----------------|
| 1 | [architecture.md](architecture.md) | Position in the fleet, trust model, request path, deployment shape |
| 2 | [tokens.md](tokens.md) | Access/refresh design, JWT claims, rotation + theft detection (and the two bugs it caught) |
| 3 | [accounts.md](accounts.md) | Identity vs login methods, email↔Discord linking flows, guard rails |
| 4 | [data-model.md](data-model.md) | Every table, indexes, ER sketch, conventions |
| 5 | [api.md](api.md) | Live + designed endpoints, error envelope |
| 6 | [configuration.md](configuration.md) | Env vars (what/why/what-if-missing), per-env setups, RS256 keys |
| 7 | [testing.md](testing.md) | The 53-test suite: layers, fixtures, what each test pins, how to extend |
| 8 | [security.md](security.md) | Hashing, cookies, CSRF stance, revocation story, known gaps |
| 9 | [roadmap.md](roadmap.md) | Phase A–E status + deferred items |

## Topic shortcuts

- *"How would OAuth + email linking actually work?"* → [accounts.md](accounts.md)
- *"Why did my refresh token stop working everywhere?"* → [tokens.md](tokens.md)
- *"Where am I logged in / how do I kick a device?"* → [api.md](api.md#2-live-endpoints)
- *"What does error code X mean?"* → [api.md](api.md)
- *"Which env vars do I need for prod?"* → [configuration.md](configuration.md)
- *"What do the tests check and how do I add one?"* → [testing.md](testing.md)
- *"What's still insecure?"* → [security.md](security.md)

## Repo-level docs

- [`docs/ARCHITECTURE.md`](../../../docs/ARCHITECTURE.md) — fleet-wide decisions
- [`docs/V1-SCHEMAS.md`](../../../docs/V1-SCHEMAS.md) — the v1 better-auth schema this service was ported from
- [`DEV.md`](../../../DEV.md) — repo-wide dev guide
