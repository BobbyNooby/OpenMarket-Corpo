# Testing

> [catalogue](../README.md) › Testing

The 31-test suite: what it really pins, how the fixtures work, and —
honestly — what it does not.

## Contents

- [Layers](#layers)
- [The fixture](#the-fixture)
- [What key tests pin](#what-key-tests-pin)
- [The 2026-09-03 fault-injection session](#the-2026-09-03-fault-injection-session)
- [Deliberately NOT tested](#deliberately-not-tested)

---

## Layers

| Layer | Count | Where | How |
|---|---|---|---|
| Integration | 28 | `Catalogue.Tests/SecurityAndFlowTests.cs` | WebApplicationFactory over the real `Program` + Testcontainers Postgres 17, real RSA-signed JWTs through the real JwtBearer pipeline |
| Unit | 3 | `Catalogue.Tests/IntrospectionMappingTests.cs` | pure — no container, no app host; pins the introspection mapping contract |

Total 31 xUnit tests (`Catalogue.slnx`; CI runs `dotnet test Catalogue.Tests`
explicitly). The audit on 2026-09-03 confirmed 31/31 pass.

The unit tests exist because the introspection mapping is the trust
boundary: a malformed user id from auth must fail closed (`null` → 503),
never fabricate a `Guid.Empty` owner for listings and trades — and that
holds even for a rejection verdict, since an unparseable id on an
`active=false` answer is still a contract break
(`IntrospectionMappingTests.cs:1-9, 35-41`).

## The fixture

`CatalogueFixture.cs` is a collection fixture (`IAsyncLifetime`):

- **Real Postgres, real parser.** A `postgres:17` Testcontainers container;
  the connection is handed to the app as a **libpq-shaped `DATABASE_URL`**
  so the same `DatabaseUrl` parser production uses is what's exercised
  (:66-68). Migrations + seeding run once at factory boot (:80-81).
- **Real JWT pipeline.** Tests mint RS256 tokens with a fixture RSA key;
  the public half is injected as `IssuerSigningKey` via
  `PostConfigure<JwtBearerOptions>`, so no JWKS fetch happens but every
  other validation step (signature, issuer `auth`, audience `openmarket`,
  the Guid-`sub` gate) is the production path (:53-55, 74-77).
- **Controlled introspector.** The real `GrpcIntrospector` is replaced by
  `FakeIntrospector`: deterministic per-token registrations, plus an
  `Override` hook where returning `null` means "auth unavailable" — the
  fail-closed 503 path (:110-162).
- **`GuidUtility.FromName`** maps usernames to stable uuids, so
  `token-for-alice` is always the same buyer (:168-175).
- **`CapturedErrors`** keeps app-side error+ log lines in memory so a
  failing test can print *why* a request 500'd (:41-42, 181-202).

## What key tests pin

Highlights from `SecurityAndFlowTests.cs` (grouped; names are the source
of truth):

| Concern | Test(s) | What is actually asserted |
|---|---|---|
| Competing accepts | `competing_accepts_yield_exactly_one_trade` | both accepts fire concurrently; exactly one 201 + one 409; **DB count of trades for the listing equals 1** (asserted against the DbContext, not the HTTP surface); the winner's retry replays 200; a third buyer gets 409 |
| Idempotency replay | `idempotency_key_replays_same_listing_and_rejects_body_mismatch`, `accept_key_reused_on_other_listing_conflicts`, `create_replay_with_garbage_order_type_conflicts_409`, `keyless_accepts_do_not_collide_on_unique_index` | 200 replay on same key+body; 409 on amount/orderType/garbage-enum mismatch; cross-listing key reuse → 409; keyless accepts by one buyer across two listings never collide on the unique index |
| Ban gate | `banned_user_mutation_fails_closed_403`, `banned_user_watchlist_delete_fails_closed_403` | `active=false` verdict → 403, including on "unwatch" (policy parity: banned users get no write path at all) |
| Fail-closed outage | `introspection_outage_fails_closed_503` | introspector returning `null` → 503, never a fallback allow |
| xmin persistence pin | `stale_patch_on_sold_listing_conflicts_instead_of_rewriting` | **at the persistence layer**: tracked load → out-of-band flip to Sold → stale `SaveChangesAsync` must throw `DbUpdateConcurrencyException`, and the sold terms stay intact. HTTP-level interleaving can't place the accept between the PATCH's load and save, so the pin lives where the race is real (:541-566) |
| Lapsed accept | `accept_lapsed_listing_is_410_even_before_sweep` (+ `expired_listing_accept_is_410_after_sweep`) | accept on an expired-but-unswept listing → 410 and **no trade row slips through** in that window; after a forced sweep, also 410 |
| Readiness honesty | `health_ready_degrades_503_on_outbox_backpressure` | 11k unpublished outbox rows → `/health/ready` answers 503 |
| Full-replace PATCH | `patch_can_switch_requested_kind` | switching requested item→currency is an ordinary update under the full-replace contract |
| Timestamp guard | `expires_at_without_timezone_is_400_not_500`, `expires_at_with_explicit_offset_is_400_not_500` | Unspecified-Kind and offset-carrying (Local-Kind) strings 400 instead of Npgsql 500ing at write time |
| Authn/authz basics | `anonymous_mutation_is_401`, `expired_token_is_401_on_mutation`, `plain_user_cannot_create_items`, `owner_role_can_create_items_and_browse_shows_them`, `author_cannot_accept_own_listing`, `retired_item_hidden_from_browse_but_still_resolvable`, etc. | deny-by-default boundary, admin-or-owner ladder, self-accept 409, retire = hide-but-resolvable |

## The 2026-09-03 fault-injection session

The test-quality audit (`docs/AUDIT-2026-09-03.md`, addendum) verified the
suite isn't gamed — no tautologies, no mock-echo assertions, and the race
test is real (concurrent accepts, DB count == 1) — then went further and
**deliberately broke load-bearing code** to prove suites go red:

| Injected fault | Result |
|---|---|
| catalogue: delete the in-transaction expiry predicate from the FCFS accept (`ListingEndpoints.cs:317`) | **PASSES — real blind spot** |
| (gateway, frontend, auth equivalents) | RED, as expected |

The blind spot: `accept_lapsed_listing_is_410_even_before_sweep` pins the
**pre-check** path (`ListingEndpoints.cs:307-309`), but the in-transaction
expiry predicate inside the conditional UPDATE — the closure for the
check-vs-update lapse race — can be deleted with zero test failures
(verified by injection with a clean rebuild). Same structural hole the
team had already fixed for xmin in commit `3d59490`: that commit moved the
stale-PATCH pin from an HTTP-level test (which exited at the pre-check and
never exercised the xmin token) to the persistence level, with the commit
message noting the test is mutation-checked (fails without the fix, passes
with it).

## Deliberately NOT tested

The UNGUARDED registry for catalogue — behavior the docs claim but no
test would fail if it regressed. Tracked, not ignored:

- **The in-tx expiry predicate** (above) — top debt.
- **HTTP 409 mapping of the xmin concurrency exception** — the exception
  path is pinned at persistence level only; the
  `DbUpdateConcurrencyException` → `409 listing_conflict` translation in
  `ListingEndpoints.cs:214-221` has no HTTP-level test.
- **ILIKE search escaping** (`%`/`_`/`\`, `ListingEndpoints.cs:71`).
- **Cross-user scoping of PATCH, watchlist and trades endpoints** — the
  ownership predicates exist but only the accept path is adversarially
  tested.

Planned fix (audit rec. 1, 4): give the accept path an **injectable
clock** and pin the expiry predicate at persistence level like the xmin
pin; then add the cheap missing pins (escaping and cross-user scoping are
one-liners each).

Also untested by design at this layer: the Kafka relay (doesn't exist —
see [architecture.md](architecture.md#events-and-the-outbox)), and
outbox-row contents (only readiness depth is pinned).

---

Related: [security.md](security.md) · [architecture.md](architecture.md)
