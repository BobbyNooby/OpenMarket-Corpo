# Catalogue Pre-Commit Fixes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the audit findings (H1–H3, key mediums/lows) in the uncommitted
catalogue WIP and land it as clean, reviewable commits — using v1
(`~/Repositories/OpenMarket`) as the design reference where noted.

**Architecture:** C# / ASP.NET Core 10 minimal API, EF Core + Npgsql (Postgres
17), JWT-bearer + gRPC introspection edge auth (fail-closed), Testcontainers
xUnit suite. No Kafka/events in this pass (deliberately deferred).

**Tech Stack:** .NET 10, EF Core 10, Npgsql, Grpc.Net.Client, xUnit +
WebApplicationFactory + Testcontainers.PostgreSql.

**Spec:** [2026-09-02-v1-v2-catalogue-comparison.md](2026-09-02-v1-v2-catalogue-comparison.md)
(argues the update-contract and snapshot decisions) + audit findings recorded in
`log.md` session entry 2026-09-02.

## Global Constraints

- **Concurrency guard:** the user's recursive catalogue agent may still be
  running. Task 0 is MANDATORY and first: confirm the tree is quiet, checkpoint
  it, and re-check which tasks are already done. Never start mid-pass.
- **Never weaken a test to make it pass.** If a pinned behavior seems wrong,
  stop and surface it — do not edit the assertion to match the code.
- Test run requires Docker (Testcontainers). Verify Docker is up before Task 1.
- `launchSettings.json` is a catalogue file: this plan is the ONLY thing
  allowed to touch `services/catalogue/**` right now.
- Commits: one logical chunk per task, conventional style matching repo
  history (`fix(catalogue): …`), only after the task's tests are green.
- Suite command: `dotnet test services/catalogue/Catalogue.Tests` from repo
  root (or `dotnet test` inside `services/catalogue`). Baseline expectation is
  defined in Task 0.

---

### Task 0: Quiesce, verify state, checkpoint-commit the WIP

**Files:**
- Read-only: `git status`, test suite output
- Create: none

**Interfaces:**
- Produces: a clean git baseline commit of the agent's output, and a decision
  list of which tasks below are already done.

- [ ] **Step 1: Confirm the recursive agent is done.** Ask the user, or check
  for recent file mtime changes over a 5-minute window inside
  `services/catalogue/`. Do NOT proceed while files are actively changing.
- [ ] **Step 2: Run the suite and record the baseline.**

  ```bash
  dotnet test services/catalogue/Catalogue.Tests 2>&1 | tail -20
  ```

  Expected per audit: RED — admin-path tests 403 (fixture H2) and the
  replay-mismatch test fails (H3). Record the exact failing set; this is the
  yardstick for "no regressions" later.
- [ ] **Step 3: Checkpoint-commit everything as-is** (this is the revert
  baseline for every later task):

  ```bash
  git add contracts/proto services/gateway/internal/authpb services/catalogue
  git commit -m "feat(catalogue): WIP vertical slice — domain, endpoints, edge auth, tests (agent pass 1)"
  ```
- [ ] **Step 4: Re-verify which findings are already fixed.** Check:
  `CatalogueDbContext.cs` trades index for `HasFilter` (H1 done?);
  `CatalogueFixture.FakeIntrospector` for role support (H2 done?);
  `ListingEndpoints` create-replay path for a body hash (H3 done?);
  `Properties/launchSettings.json` port (8081?). Mark tasks below SKIP or DO
  accordingly and note the decision in the commit that closes each.

### Task 1 (H2): Make the test fixture able to express roles

The suite is red because `FakeIntrospector.IntrospectAsync` always returns
`roles: ["user"]`; every admin-path test 403s. Fix the fixture, not the tests.

**Files:**
- Modify: `services/catalogue/Catalogue.Tests/CatalogueFixture.cs` (`FakeIntrospector`)
- Test: `services/catalogue/Catalogue.Tests/SecurityAndFlowTests.cs` (no changes — existing tests are the spec)

**Interfaces:**
- Produces: `TokenFor(name, roles, …)` registrations that `IntrospectAsync`
  honors — i.e. a token→(active, user, roles) registry keyed by token string.

- [ ] **Step 1: Implement.** Replace the unused `configured` field with a
  `ConcurrentDictionary<string,(bool active, Guid user, string[] roles)>
  Registry`; `TokenFor` stores the entry it mints; `IntrospectAsync` looks up
  `Registry` first (fallback: current default). Keep `Override` for the
  outage/403 scenarios that already use it.
- [ ] **Step 2: Run the suite.**

  ```bash
  dotnet test services/catalogue/Catalogue.Tests 2>&1 | tail -20
  ```

  Expected: admin-path tests flip from 403-failures to green or to H3's single
  failure. `owner_role_can_create_items_and_browse_shows_them` must PASS.
- [ ] **Step 3: Commit**

  ```bash
  git add services/catalogue/Catalogue.Tests/CatalogueFixture.cs
  git commit -m "test(catalogue): fake introspector honors registered roles — suite runs again"
  ```

### Task 2 (H3): Idempotency replay must compare the request body

v1 reference: no idempotency at all — this is v2-new, so v2 defines the
contract: same key + same intent = replay (200), same key + different intent
= 409. The test `idempotency_key_replays_same_listing_and_rejects_body_mismatch`
already pins this (currently red).

**Files:**
- Modify: `services/catalogue/Domain/Marketplace.cs` (add `RequestHash` to `Listing`)
- Modify: `services/catalogue/Endpoints/ListingEndpoints.cs:108-114` (create replay path)
- Modify: `services/catalogue/Infrastructure/CatalogueDbContext.cs` (column, index unchanged)
- Test: `services/catalogue/Catalogue.Tests/SecurityAndFlowTests.cs` (existing test is the spec; add nothing yet)

**Interfaces:**
- Produces: `Listing.RequestHash : string` (SHA-256 over the canonical JSON of
  the create body, hex, lowercase). Replay compare: constant content → 200
  `{replay:true}`; mismatch → `409 idempotency_key_conflict`.

- [ ] **Step 1 (red):** Confirm the pinned test fails against current code:

  ```bash
  dotnet test services/catalogue/Catalogue.Tests --filter FullyQualifiedName~idempotency_key_replays 2>&1 | tail -10
  ```

  Expected: FAIL (replay returns 200 for a mismatched body).
- [ ] **Step 2 (green):** Add the column (nullable, backfill `''`), compute
  `Convert.ToHexString(SHA256.HashData(JsonSerializer.SerializeToUtf8Bytes(body, webOpts)))`
  in create, store on insert, and compare on replay: equal → replay 200;
  different → `Results.Json(…, 409)`. Regenerate the migration
  (`dotnet ef migrations add ListingRequestHash`) — keep the existing
  `InitialCreate` naming convention.
- [ ] **Step 3:** Re-run the filter test → PASS, then the full suite → no new
  failures vs the Task 0 baseline.
- [ ] **Step 4: Commit**

  ```bash
  git add services/catalogue
  git commit -m "fix(catalogue): idempotency replay hashes the create body — mismatch is 409"
  ```

### Task 3 (H1): Trades idempotency index needs the empty-key filter

The listings index filters `idempotency_key <> ''`; the trades twin doesn't,
so a user's *second* keyless accept = unique-violation → 500, permanently
stuck until they send a key.

**Files:**
- Modify: `services/catalogue/Infrastructure/CatalogueDbContext.cs:114`
- Modify: `services/catalogue/Endpoints/ListingEndpoints.cs` (accept: synthesize key when absent — defense in depth)
- Test: `services/catalogue/Catalogue.Tests/SecurityAndFlowTests.cs` (new test)

**Interfaces:**
- Produces: accept contract — `Idempotency-Key` optional; when absent, server
  stores `accept:{userId}:{listingId}` (deterministic, collision-free per
  user+listing) so the filtered index is never hit with `''`.

- [ ] **Step 1 (red):** Add the failing test — two sequential accepts by the
  same user on two different listings, neither sending `Idempotency-Key`;
  second must be 200/201, not 500:

  ```csharp
  [Fact]
  public async Task keyless_accepts_do_not_collide_on_unique_index()
  {
      // two active listings by token-alice; token-bob accepts both, no key header
      var first  = await client.PostAsync($"/listings/{l1.Id}/accept", null);
      var second = await client.PostAsync($"/listings/{l2.Id}/accept", null);
      Assert.NotEqual(HttpStatusCode.InternalServerError, second.StatusCode);
      Assert.True(second.IsSuccessStatusCode);
  }
  ```

  Run: `dotnet test … --filter FullyQualifiedName~keyless_accepts` → FAIL with 500.
- [ ] **Step 2 (green):** Add `HasFilter("idempotency_key <> ''")` to the
  trades index + regenerate migration; synthesize the deterministic key in the
  accept endpoint when the header is absent. Re-run → PASS.
- [ ] **Step 3:** Full suite → no new failures.
- [ ] **Step 4: Commit**

  ```bash
  git add services/catalogue
  git commit -m "fix(catalogue): filtered trades idempotency index + synthesized accept keys"
  ```

### Task 4 (M1): Accept must reject lapsed-but-unswept listings

Mirror the scanner's DB-clock predicate in the accept gate.

**Files:**
- Modify: `services/catalogue/Endpoints/ListingEndpoints.cs:260-273` (accept gate)
- Test: `services/catalogue/Catalogue.Tests/SecurityAndFlowTests.cs` (new)

**Interfaces:**
- Produces: accept on a listing whose `ExpiresAt <= now()` (status still
  Active) → `410 listing_expired`, no state change.

- [ ] **Step 1 (red):** New test: create listing with `expiresAt` 1s in the
  future, wait ~2s, accept → expect `410 Gone` (today: 200 + sale).
- [ ] **Step 2 (green):** Add `AND (expires_at IS NULL OR expires_at > now())`
  to the conditional `ExecuteUpdateAsync` predicate; map zero rows updated with
  a re-read status of Active+expired to 410. Run → PASS.
- [ ] **Step 3:** Full suite green. Commit:
  `fix(catalogue): accept rejects expired-in-window listings (410)`.

### Task 5 (M2): MaxHorizon computed per request

`static readonly DateTime MaxHorizon = DateTime.UtcNow.AddDays(30)` freezes at
process start — after 30 days of uptime every dated create 400s.

**Files:**
- Modify: `services/catalogue/Endpoints/ListingEndpoints.cs:21`

- [ ] **Step 1:** Inline it: inside `ValidateAsync`, compute
  `var maxHorizon = DateTime.UtcNow.AddDays(30);`
- [ ] **Step 2:** Suite still green (no test pins the old behavior; the
  validator is exercised indirectly). Commit:
  `fix(catalogue): expiry horizon computed per request, not per process`.

### Task 6 (M3): `/health/ready` degraded branch must return 503

The `depth > 10_000` result is computed and discarded.

**Files:**
- Modify: `services/catalogue/Program.cs:97-99`
- Test: new — set outbox depth via seeded rows in a dedicated test, expect 503;
  and normal path expects 200.

- [ ] **Step 1 (red):** test asserting 503 on deep outbox → FAIL (200 today).
- [ ] **Step 2 (green):** `return depth > 10_000 ? Results.Json(degraded, 503) : Results.Json(ready);` → PASS. Commit:
  `fix(catalogue): /health/ready surfaces outbox backpressure as 503`.

### Task 7 (M4): Unparseable introspected user id fails closed

`Guid.TryParse(resp.UserId, out var id) ? id : Guid.Empty` fabricates an
identity that then owns listings.

**Files:**
- Modify: `services/catalogue/Auth/Introspection.cs:45`
- Test: new — fake introspector override returning `user_id: "not-a-guid"` →
  mutation expects 503.

- [ ] **Step 1 (red):** test → FAIL (200 today).
- [ ] **Step 2 (green):** return `null` on parse failure (same path as gRPC
  failure → `Edge.RequireLiveAsync` 503). Also catch remaining `RpcException`
  flavors (`Internal`, `Unknown`) → `null` (audit L1). Run → PASS. Commit:
  `fix(catalogue): malformed introspection fails closed instead of Guid.Empty`.

### Task 8 (M5): No silent dev fallbacks outside Development

**Files:**
- Modify: `services/catalogue/Program.cs:20,24`

- [ ] **Step 1:** At startup, if `builder.Environment.IsProduction()` and
  (`POSTGRES_PASSWORD` or `GRPC_INTERNAL_SECRET` unset) → throw with an
  explicit message. Dev defaults remain for Development.
- [ ] **Step 2:** Suite green (fixture supplies values). Commit:
  `fix(catalogue): fail fast when prod secrets are missing`.

### Task 9 (L3 + L4): Full-replace update contract (adopt v1's PUT) + watchlist ban-check

v1's `PUT /:id` full-replace is the reference contract (see comparison doc §2
Update) — v2's merge-PATCH cannot clear or switch XOR fields.

**Files:**
- Modify: `services/catalogue/Endpoints/ListingEndpoints.cs:167-168,401-403`
- Modify: `services/catalogue/Endpoints/MeEndpoints.cs:80-89` (L4: add `RequireLiveAsync`)
- Test: new — PATCH clearing `requestedItemId` (switch to currency) succeeds;
  banned user watchlist DELETE → 403.

- [ ] **Step 1 (red):** both tests → FAIL (400 / 200-today).
- [ ] **Step 2 (green):** switch the update handler to replace semantics
  (absent field = clear; offered lines deleted + re-inserted in the same tx,
  mirroring v1) and add the missing ban-check call. Run → PASS.
- [ ] **Step 3:** Full suite green. Commit:
  `fix(catalogue): full-replace listing update; watchlist delete requires live user`.

### Task 10: Nits sweep + docs, then hand-back

**Files:**
- Modify: `services/catalogue/Program.cs:2-3` (duplicate using),
  `Endpoints/ListingEndpoints.cs` (drop duplicate `/listings/me/listings` route,
  snapshot serializer options → Web defaults, dead `Transition` param),
  `Properties/launchSettings.json` (:8081)
- Modify: `services/catalogue/README.md` (record FCFS-accept decision +
  Current state section refresh)

- [ ] **Step 1:** Apply nits; suite green.
- [ ] **Step 2:** Final full run + parity checklist from comparison doc §2
  Browse (record which v1 filters exist / don't — do NOT implement new filters
  in this pass).
- [ ] **Step 3:** Commit: `chore(catalogue): nit sweep + dev port 8081 + FCFS decision recorded`.

## Self-Review

- Spec coverage: comparison §2 Update→T9, §2 Sell→T3/T4, §4 feeds→T10; audit
  H1–H3→T1–T3, M1–M5→T4–T8, L3/L4→T9, nits→T10. Renew endpoint + browse
  filters deliberately NOT here (backlog, post-M3).
- Placeholders: none — every step has code shapes or exact commands.
- Type consistency: `RequestHash` naming consistent between T2 interface block
  and steps; fixture registry names consistent with Task 1.
