# Repo Hygiene & Docs Truth Sweep — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the repo's CI and docs tell the truth: fix the false README
claims, add the missing .NET CI coverage, close the compose port exposure —
everything the 2026-09-02 audit flagged OUTSIDE `services/catalogue/`.

**Architecture:** pure docs + workflow/Makefile/compose edits. No service code,
no test runs required (CI validity is checked by inspection + `docker compose
config`; docs claims are verified by grep against reality).

**Tech Stack:** GitHub Actions YAML, Docker Compose, Make, Markdown.

**Spec:** audit findings 2026-09-02 (see `log.md` session entry; drift table
reproduced per-task below). Related:
[comparison doc](2026-09-02-v1-v2-catalogue-comparison.md).

## Global Constraints

- **PRECEDENCE — read first:** the catalogue recursion agent EXPANDED SCOPE
  on 2026-09-02 (evidence: it added `gateway/internal/upstream/catalogue/`,
  a `dotnet` CI job, Makefile catalogue targets, catalogue compose env, and
  flow-test §15 while wiring "catalogue through the gateway"). Plan B executes
  ONLY after (a) that agent has fully finished, (b) its work is checkpointed
  in git, and (c) Task 0 below has re-classified every task as SKIP or DO.
- **FORBIDDEN SET during any concurrent agent run:** `services/catalogue/**`,
  `contracts/proto/**`, `services/gateway/internal/authpb/**`, `log.md`,
  plus the agent's new working set (`.github/workflows/ci.yml`, `Makefile`,
  `deploy/compose/docker-compose.yml`, `services/gateway/**`,
  `services/auth/scripts/flow-test.sh`). After every task:
  `git status --short` must show zero paths in the forbidden set before committing.
- **No service restarts, no `docker compose up`** — `docker compose config`
  (validate-only) is allowed.
- Every docs fix must verify the claim against reality *at fix time* (grep the
  code, count the things) — no copying numbers from this plan blindly; this
  repo's disease is stale numbers.
- Commit style: repo convention (`docs: …`, `ci: …`, `chore(compose): …`).

---

### Task 0: Re-baseline against the agent's expanded output

**Files:** read-only (`git status`, `git diff`, greps)

- [ ] **Step 1:** Confirm the catalogue agent is done (quiet tree, user
  confirms). Checkpoint its output first (one commit covering
  catalogue + gateway wiring + CI/compose/Makefile/flow-test edits).
- [ ] **Step 2:** Classify each task below SKIP / DO / REDUCE against the
  tree. Known as of 2026-09-02 evening: agent already added the dotnet CI job
  (no admin compile, no cron, no gate-decision), Makefile test/lint catalogue
  targets (admin still missing), catalogue compose env (ports/loopback +
  image pinning untouched). Re-verify all of this at run time — it may have
  gone further.
- [ ] **Step 3:** Also review the agent's gateway mount (`internal/upstream/
  catalogue/mount.go`) against the auth upstream's sanitization pattern
  before declaring the gateway docs tasks' assumptions still valid.

### Task 1: CI honesty — add .NET + admin, add schedule, document the gate

**Files:**
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Produces: `catalogue` job runnable locally via `act`-equivalent mental model;
  `dependency-scan` gains a weekly cron trigger.

- [ ] **Step 1:** Add a `catalogue` job: `actions/setup-dotnet` (.NET 10 SDK) →
  `dotnet test services/catalogue/Catalogue.Tests` (note in a comment: needs
  Docker; GitHub hosted runners have it). Wire it into any existing
  `ci-success`/meta job if one exists. *(Agent already added this job on
  2026-09-02 — likely SKIP; verify it runs the Tests project, not just build.)*
- [ ] **Step 2:** Add an admin compile job (or extend the auth Java job into a
  matrix): `mvn -q compile` for `services/admin` — no tests exist yet; compile
  only, with a TODO comment pointing at the future test suite.
- [ ] **Step 3:** Add `schedule: [{cron: "0 3 * * 1"}]` (Mondays 03:00 UTC) to
  triggers. Keep push/PR triggers.
- [ ] **Step 4:** Decide + document the OWASP gate: either drop
  `continue-on-error: true` (real gate) or keep advisory but change the job
  name to `dependency-scan-advisory` and fix the README claim accordingly in
  Task 3. Default: keep advisory (flaky OWASP gates poison CI) + honest naming.
- [ ] **Step 5:** Verify: YAML parses (`ruby -ryaml -e 'puts YAML.load_file(".github/workflows/ci.yml")' > /dev/null` or `yq`), forbidden set clean, commit:

  ```bash
  git add .github/workflows/ci.yml
  git commit -m "ci: catalogue test job + admin compile + weekly scan schedule + honest gate naming"
  ```

### Task 2: Compose — loopback-only ports, pinned images

**Files:**
- Modify: `deploy/compose/docker-compose.yml`

- [ ] **Step 1:** Prefix Jaeger's three ports (16686, 4317, 4318) and Mailpit's
  two (8025, 1025) with `127.0.0.1:` exactly like postgres already does.
- [ ] **Step 2:** Pin `jaegertracing/all-in-one:latest` and
  `axllent/mailpit:latest` to concrete versions (pick current stable; record
  the tag in the commit message).
- [ ] **Step 3:** Validate + commit:

  ```bash
  docker compose -f deploy/compose/docker-compose.yml config > /dev/null && echo OK
  git add deploy/compose/docker-compose.yml
  git commit -m "chore(compose): dev tools loopback-only, pin jaeger/mailpit versions"
  ```

### Task 3: README truth sweep

**Files:**
- Modify: `README.md`

Fix each false/stale claim (verify each against reality before writing):
- [ ] **Step 1:** "CI matrix across all four languages" — true again after
  Task 1; reword to match what actually runs (incl. admin compile-only).
- [ ] **Step 2:** Test counts: replace "201 tests" with the number
  `grep -rc '@Test\|@ParameterizedTest' services/auth/src/test | …` yields
  today; replace "126-step flow test" with the count of `step()` calls +
  inline assertions in `services/auth/scripts/flow-test.sh` (or make the
  script print its total and cite that — preferred, self-maintaining).
- [ ] **Step 3:** "five stubs"/"five stubbed prefixes" → four (admin is
  proxied to auth; see `services/gateway/main.go:82-85`).
- [ ] **Step 4:** Future-tense qualifiers: Schema Registry, MinIO, event
  protos, OpenAPI specs → mark "(Phase 4)" / "(lands as domains ship)" so the
  present-tense table stops describing infra that doesn't exist.
- [ ] **Step 5:** Repo layout tree: `deploy/k8s/` → mark "(placeholder, Phase 5)".
- [ ] **Step 6:** Commit: `docs: README truth sweep — counts, stub counts, future-tense infra`.

### Task 4: ARCHITECTURE.md + auth roadmap accuracy

**Files:**
- Modify: `docs/ARCHITECTURE.md`, `services/auth/docs/roadmap.md`

- [ ] **Step 1:** ARCHITECTURE.md: real gRPC contract names —
  `openmarket.auth.v1.AuthService` (`GetUser`, `IntrospectToken`), not
  `om.auth.v1.UserService` (`GetUserById`/`StreamUsersByIds`); MinIO /
  Prometheus / Schema Registry mentions → future-tense consistent with Task 3.
- [ ] **Step 2:** auth roadmap Phase G: rewrite as *partially shipped* —
  IntrospectToken over gRPC on :9090 is LIVE (commits `e82aec0`, `767e77b`);
  remaining: `GetUser` + internal-call identity metadata; delete the stale
  deferred-table line claiming gRPC is fully "later".
- [ ] **Step 3:** Link-check relative links in edited docs (they were
  programmatically verified once — keep that true). Commit:
  `docs: architecture + auth roadmap match shipped gRPC reality`.

### Task 5: Gateway docs — claims vs code

**Files:**
- Modify: `services/gateway/docs/edge-auth.md`, `docs/testing.md`,
  `docs/architecture.md`, `services/gateway/README.md`

- [ ] **Step 1:** edge-auth.md:67 — "WaitForReady off" is false
  (`middleware/auth.go:112` passes `WaitForReady(true)`). Fix the doc to
  describe actual behavior AND add a `KNOWN ISSUE` line pointing at the
  fail-fast fix (do NOT change the Go code in this plan — gateway code edits
  get their own slice).
- [ ] **Step 2:** README/roadmap/architecture stub counts four-not-five (align
  with Task 3 Step 3); "502 on upstream failures" → scope to refused/broken
  connections, note the hung-upstream gap (`ResponseHeaderTimeout` missing).
- [ ] **Step 3:** testing.md XFF mutation-table row → replace with the honest
  note (stdlib strips inbound XFF; `Set` after Rewrite is the guarantee).
- [ ] **Step 4:** Commit: `docs(gateway): edge-auth and routing docs match code`.

### Task 6: DEV.md + Makefile completeness

**Files:**
- Modify: `DEV.md`, `Makefile`

- [ ] **Step 1:** DEV.md: close the broken ```bash block (~line 94), move the
  dev-harness prose out of it, fix orphaned duplicate `make …` lines;
  presence example gains `--port 8083`.
- [ ] **Step 2:** Makefile: `test` gains
  `cd services/catalogue && dotnet test`; `lint` gains `dotnet build` for
  catalogue and `mvn -q compile` for admin (mirror Task 1's CI shape so local
  and CI agree).
- [ ] **Step 3:** Verify: `make -n test lint` lists the new targets without
  executing; commit: `docs(dev)+make: fix broken block; make test/lint cover all four languages`.

### Task 7: Final audit-lens pass

- [ ] **Step 1:** Re-run the drift table from the audit (grep each fixed claim
  against reality) — everything must be ✅.
- [ ] **Step 2:** `git log --stat` review: confirm zero forbidden-set paths in
  any commit of this plan.
- [ ] **Step 3:** Append results to the session log (`log.md`) — this is the
  one sanctioned `log.md` touch, single-writer at plan end.

## Self-Review

- Coverage: every audit hygiene finding (H1–H4, M1–M5, L1–L5 hygiene list) has
  a task; gateway *code* defects deliberately excluded (separate slice).
- Placeholders: none; counts that could go stale are defined by the command
  that computes them, not by a literal.
- Consistency: CI job shape (Task 1) matches Makefile shape (Task 6); stub
  count fixed once in README and once in gateway docs consistently.
