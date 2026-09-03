# v1 ↔ v2 Catalogue Comparison — design flows, verdicts, parity gaps

> **Context doc:** this is the spec/rationale behind
> [Plan A (catalogue pre-commit fixes)](2026-09-02-catalogue-precommit-fixes.md) and
> informs Phase 2 (messaging/presence). Read v1 code at `~/Repositories/OpenMarket`
> (clone of `BobbyNooby/OpenMarket` @ `f5348fa` — the exact commit the v2 schema
> transcription in `docs/V1-SCHEMAS.md` came from).

**Sources:** v1 `packages/server/src/routes/listings/{manage,shared,browse}.ts`,
`lists.ts`, `watchlist.ts`; v2 = uncommitted catalogue WIP in
`services/catalogue/` (audited 2026-09-02, see session log).

---

## 1. The headline divergence: who sells?

| | v1 (seller-driven, honor system) | v2 WIP (buyer-driven, FCFS) |
|---|---|---|
| Sell trigger | Seller picks a buyer from `GET /:id/contacts` (chat participants via `conversations.listing_id`), then `DELETE /:id {buyer_id?}` | Buyer `POST` accept; first valid accept wins |
| Competing buyers | Not a concept — seller chooses | DB-gated conditional `UPDATE` (rowcount), loser gets 409 |
| Listing after sale | **Hard-deleted**; only a names-only jsonb snapshot survives in `trades` | Kept as closed record (`Status=Sold`) + full jsonb snapshot frozen at accept |
| buyer_id | Optional, can be NULL (trade with no buyer) | Always the authenticated acceptor |
| Settlement | Off-platform, in-game, honor-system (v1 UI aids chat) | Same philosophy, enforced ledger record |

**Verdict: v2's model is strictly better on integrity** (race-safe, auditable,
no data loss). But it **silently drops v1's seller-picks-buyer product feature**
— v2 is first-come-first-served. That's fine *today* (v2 has no chat yet), but it
is a **product decision that was never written down**. This doc records it as
deliberate-FCFS; if seller-choice is wanted later it needs a
"pending-accept → seller-confirm" state machine (do NOT add casually — it
reintroduces the race v2 just solved).

**Phase 2 note (must survive into messaging):** v1 links chat to listings via
`conversations.listing_id` and powers `/:id/contacts` off it. v2's messaging
schema should keep `listing_id` on conversations even if unused at first —
it's the seam for any future seller-choice or "contacted about" feature.

## 2. Flow-by-flow comparison

### Create listing
- v1: XOR validation app-side only; `expires_at = now+30d` server-fixed;
  offered rows inserted **outside a transaction** (partial-failure orphans);
  WS `new_listing` broadcast (fire-and-forget); analytics `listing_created`.
- v2: XOR enforced **app-side AND DB-side** (CHECK constraints); transactional
  create; client-chosen `expiresAt` validated ≤30d; idempotency key with
  filtered unique index; outbox row for `listing.created` (relay deferred).
- **v2 better**, except two WIP bugs: `MaxHorizon` frozen at process start
  (v2-C M2 — v1's server-fixed 30d never had this class of bug) and the replay
  path not hashing the body (v2-C H3 — v1 had no idempotency at all, so no
  equivalent).

### Update listing
- v1: `PUT /:id` — **full-replace in one transaction**; `?? null` semantics
  naturally allow clearing/switching requested item↔currency; offered lines
  deleted + re-inserted.
- v2: `PATCH` with `??` merge — **cannot clear `RequestedItemId` or switch the
  XOR kind** (always 400s "exactly one of") — audit finding L3.
- **v1's contract is the fix shape**: adopt full-replace (or explicit
  clear-flags) for v2's update. This is the one place v1's design is simply
  right and v2 should copy it.

### Status transitions
- v1: `PATCH /:id/status` — owner-only, `sold` terminal, **no expiry check, no
  events, no transaction**, and `expired` was a manual status (nothing swept it).
- v2: proper guard chain (410 on expired resume, pause/resume, outbox events,
  atomic flip+event), plus the `ExpiryScanner` (DB-clock predicate,
  `pg_try_advisory_lock` for replica no-op). **v2 better by a wide margin.**
  One bug: accept gate lacks an `ExpiresAt > now()` check, so lapsed-but-
  not-yet-swept listings accept for ≤1 sweep period (v2-C M1). v1 had the same
  hole forever; v2 should close it.

### Renew
- v1: `PATCH /:id/renew` (+30d, active/expired only, analytics event).
- v2: **missing**. Parity gap — trivial to add once M2 (per-request horizon)
  is fixed. Queue for the catalogue close-out, not the pre-commit pass.

### Trade snapshot
- v1: names+amounts only, written at sell time, listing row destroyed.
- v2: full snapshot (requested + offered lines with ids) frozen at accept,
  listing survives as closed record. **v2 better** (v1's snapshot can't be
  resolved back to catalog entities). Nit: v2 serializes the snapshot with
  default JSON options (PascalCase) while the outbox path uses Web defaults —
  pick one deliberately (v2-C NIT).

### Browse / read path
- v1: filters (status default `active`, orderType, itemId, currencyId,
  **categoryId via items join**, **minAmount/maxAmount**, `q` → ILIKE name on
  item OR currency), sorts (newest/oldest/amount_asc/amount_desc),
  limit clamp 1–100, count+`hasMore` envelope, batched offered fetch
  (no N+1 — pattern worth keeping), author serialization with profile fields.
- v2: has pagination clamps + escaped ILIKE (audit-verified), but **filter/sort
  parity with v1 is unverified** — treat as a checklist item, not an assumption.
- Also port-worthy from v1: batched `fetchOfferedForListings` shape (v2 should
  confirm it avoids N+1 on browse).

### Have/Want lists & watchlist
- v1: `user_item_lists` (have/want, MAX_PER_LIST=50, item XOR currency entry,
  public GET per user) + separate `watchlist` routes.
- v2 WIP: `MeEndpoints` has watchlist + item-lists with caps; audit found L4
  (watchlist DELETE skips the ban-check) and untested cap/dupe paths. Semantics
  look ported; **verify have/want `list_type` duality survived** (v1 splits
  have vs want; confirm v2 didn't collapse them).

## 3. Scorecard

| Dimension | Winner | Note |
|---|---|---|
| Sell integrity (races, audit trail) | **v2** | conditional-UPDATE accept vs v1's no-op |
| Data retention on sale | **v2** | closed record vs hard delete |
| Expiry handling | **v2** | scanner + advisory locks vs manual status |
| Transactional writes | **v2** | v1 create isn't even transactional |
| Authz model | **v2** | deny-by-default + introspection vs in-session perm strings |
| Idempotency | **v2** | absent in v1 (but fix H3 first) |
| Update contract | **v1** | full-replace PUT beats v2's merge-PATCH (L3) |
| Seller product features | **v1** | renew, seller-picks-buyer, chat-link, WS broadcast — renew is a cheap port; the rest are deliberate Phase 2+ |
| Browse filters/sorts | **v1 (probably)** | v2 needs a parity pass, not a rewrite |

## 4. Feeds

- **Plan A** (pre-commit fixes): H1, H2, H3, M1, M2, M3, M4, M5, L3
  (adopt v1 full-replace), L4, snapshot-casing nit, launchSettings :8081.
- **Plan B** (non-catalogue): untouched by this comparison.
- **Backlog (post-M3 / Phase 2 notes):** renew endpoint; browse parity
  checklist; `conversations.listing_id` in messaging schema; `listing.created`
  WS/event fan-out to replace v1's `broadcastAll`; explicit FCFS decision
  recorded in catalogue docs.
