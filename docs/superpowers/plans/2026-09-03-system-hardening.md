# System Hardening — Implementation Plan (gateway / auth / catalogue / frontend stage 0)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the 2026-09-03 system-wide audit: gateway's deferred code
slice, auth's cheap hardening bundle, the catalogue deltas found when
re-checking the recursion's own integration commits, and frontend stage 0
(CI gate + vitest on the /dev harness).

**Architecture:** Go gateway (shared ReverseProxy, gRPC edge auth), Java
Spring Boot auth (RS256 + JWKS + gRPC introspection), C# catalogue (EF Core
+ xmin concurrency), Next.js 16 frontend (unwired, /dev harness only).

**Tech Stack:** as per service. Frontend adds vitest + testing-library
(devDeps only).

**Spec:** the four 2026-09-03 audit reports (gateway, auth, catalogue delta
confirmation, frontend survey) — summarized in session log; plan argues from
those findings.

## Global Constraints

- Tests green before each commit; one commit per logical unit.
- Gateway: `go build ./... && go vet ./... && go test ./...` gate.
- Auth: `mvn -q test` gate (slow — run at slice end, not per task).
- Catalogue: `dotnet test Catalogue.Tests` gate.
- Frontend: `npm run lint && npm run build && npm test` gate.
- No behavior change to public API contracts except the two deliberate ones:
  replay-mismatch garbage → 409 (was 400), naive/Local timestamps → 400
  (was 500). Everything else is internal hardening.

---

## Slice 1 — catalogue deltas

- [ ] T1.1 `expiresAt` Kind guard: reject `Kind is not Utc` (Local binds from
  "+02:00"-style offsets and Npgsql still 500s on write). Test: offset body → 400.
- [ ] T1.2 Replay-first create: parse-safe equality (lowercased enum strings,
  TryParse-fail = mismatch) BEFORE ValidateAsync; on miss → validate. Garbage
  enum on existing key → 409. Update the garbage-enum test to 409 + rename.
- [ ] T1.3 Rewrite `stale_patch_on_sold_listing...` at DbContext level:
  tracked load → out-of-band ExecuteUpdate → SaveChanges → expect
  DbUpdateConcurrencyException (the current HTTP-level test exits at the
  pre-check and never exercises xmin).
- [ ] T1.4 gateway main.go: `"admin": true` in deployed set; hoist map.
- [ ] T1.5 catalogue README: document the accept-snapshot-vs-concurrent-PATCH
  mirror race beside the FCFS decision (bounded: snapshot-is-truth, both
  actions are the seller's own).
- Commit: `fix(catalogue): replay-first semantics, real xmin pin, timestamp guard`.

## Slice 2 — gateway hardening

- [ ] T2.1 Drop `WaitForReady(true)` (auth.go) + opts pin test.
- [ ] T2.2 Shared Transport clone + `ResponseHeaderTimeout: 30s` (package var
  for tests) on proxy.New + hung-upstream 502 pin test.
- [ ] T2.3 `/health/system`: redact URL field, errgroup-parallel probes with
  1s per-probe ctx, 5s snapshot cache (mutex-guarded), boot-time backend URL
  table + hoisted deployed set passed in. Tests: no `url` key in JSON, second
  call within TTL = no additional upstream hits.
- [ ] T2.4 `IsPublic` → exact-match only + subpath test.
- [ ] T2.5 catalogue mount: fail-fast on bad CATALOGUE_URL (auth pattern),
  mount_test.go (path preservation, identity-header stripping, dead-upstream
  502), fix stale stub-mirror test in auth_test.go.
- [ ] T2.6 Cheap pins: X-Forwarded-Host / Forwarded / X-Forwarded-Proto strip.
- Commit(s): `fix(gateway): ...` per logical unit (timeout, health, mount).

## Slice 3 — auth hardening bundle

- [ ] T3.1 InternalSecretInterceptor/Application: prod fail-fast on
  dev-internal-secret (mirror catalogue Program.cs).
- [ ] T3.2 IntrospectionGrpcService: try/catch repo calls →
  `Status.UNAVAILABLE` (matches proto contract).
- [ ] T3.3 GrpcServerLifecycle: `maxConcurrentCallsPerConnection` + honest
  javadoc (shared 32-thread executor coupling).
- [ ] T3.4 EmailFlowService: dispatch reset mail off-thread after commit.
- [ ] T3.5 SecurityConfig/application.yml: profile-gate swagger + actuator
  details off dev.
- [ ] T3.6 security.md: flat-bridge threat-model wording.
- Gate: `mvn -q test` (+ flow-test if time allows). Commit: one bundle commit.

## Slice 4 — frontend stage 0

- [ ] T4.1 CI `frontend` job: npm ci, lint, build (test added in T4.4).
  Makefile lint/test targets include frontend.
- [ ] T4.2 Extract `call()` → `src/lib/dev-api.ts` (inject fetch); page uses it.
- [ ] T4.3 vitest + RTL + jsdom setup (vitest.config.ts, setup file), devDeps only.
- [ ] T4.4 Five tests: register shape, login shape + Content-Type, bodyless
  POSTs, newest-first log rendering, error branches. `"test": "vitest run"`.
- [ ] T4.5 CI job gains `npm test`.
- Commit: `feat(frontend): test gate + vitest harness coverage`.

## Post-slices

- [ ] Audit round: adversarial reviewer over the union diff of all slices;
  integrate; recurse only on HIGH.
- [ ] Scheduled next: Boot 3.3.4 → 4.1.x dedicated slice.

## Self-review

No placeholders; every task has a gate. Sequencing: catalogue (fast, unblock
suite) → gateway → auth (slow gate last of backend) → frontend (independent).
