# Security notes

> [catalogue](../README.md) › Security

The defensive posture: what protects what, and the known gaps from the
2026-09 audit ([`docs/AUDIT-2026-09-03.md`](../../../docs/AUDIT-2026-09-03.md)).

## Contents

- [Deny-by-default](#deny-by-default)
- [Identity derivation](#identity-derivation)
- [Fail-closed introspection](#fail-closed-introspection)
- [Ownership in the query predicate](#ownership-in-the-query-predicate)
- [Idempotency replay semantics](#idempotency-replay-semantics)
- [Search escaping](#search-escaping)
- [Known gaps](#known-gaps)

---

## Deny-by-default

The authorization fallback policy is the default policy — every endpoint
requires authentication unless it is explicitly marked `AllowAnonymous`
(`Program.cs:86-90`). The open surface is exactly: health probes, `/`,
OpenAPI, public browse/search endpoints, and nothing else. A new endpoint
forgotten without an auth call is **401 by default**, not open.

## Identity derivation

The acting user always comes from the validated JWT's `sub` claim
(`Edge.Sub`, `Endpoints/Edge.cs:62-63`) — never from the request body,
query, or any forwardable header. Two layers make `Edge.Sub` total:

- RS256 signature validation with `ValidAlgorithms = ["RS256"]` (an
  alg-confusion hard stop), issuer `auth`, audience `openmarket`
  (`Program.cs:54-58`).
- `OnTokenValidated` requires `sub` to parse as a Guid or the token fails
  outright, so no handler ever sees an identity that couldn't be a real
  user id (`Program.cs:78-83`).

Roles come from introspection, not the JWT (`Edge.IsCatalogAdmin`,
`Edge.cs:29-30`): `admin` or `owner` gates catalog mutations — so a fresh
role revocation takes effect on the next mutation, not on token expiry.

## Fail-closed introspection

Every mutation calls auth's `IntrospectToken` gRPC (2 s deadline,
`x-internal-secret` header) and maps the verdict through
`GrpcIntrospector.Map` (`Auth/Introspection.cs`):

| Condition | Result |
|---|---|
| auth unreachable, deadline exceeded, secret mismatch, any `RpcException` | `null` → **503 `service_unavailable`** (`Introspection.cs:46-52`, `Edge.cs:40-45`) |
| verdict `active=false` (banned/deleted) | **403 `forbidden`** (`Edge.cs:46-48`) |
| verdict carries an unparseable user id | `null` → 503 — auth lying about identity fails closed; a `Guid.Empty` owner that could own listings and trades must never be minted (`Introspection.cs:55-64`) |
| healthy verdict | identity stashed in `HttpContext.Items`; handler proceeds |

No gRPC failure ever surfaces as a 500 — infrastructure trouble is
deliberately indistinguishable from "auth is down" to the caller
(`Introspection.cs:47-52`).

## Ownership in the query predicate

Ownership is never checked as a separate step after a fetch — it is part
of the query itself, so the check and the read are one atomic predicate:

- PATCH loads via `Where(l => l.Id == id && l.AuthorId == sub)`
  (`ListingEndpoints.cs:187-191`) — a foreign listing is indistinguishable
  from a missing one (404, no existence leak).
- cancel / pause / resume use conditional `ExecuteUpdate` with
  `AuthorId == sub` in the predicate (`ListingEndpoints.cs:230-262`).
- trade reads filter `SellerId == sub || BuyerId == sub`; a non-participant
  gets 404 (`ListingEndpoints.cs:381-383`).
- watchlist and item-list writes/deletes always carry `UserId == sub`
  (`MeEndpoints.cs:87-89, 178-180`).

The stale-write direction is guarded by the `xmin` row-version: a PATCH
whose tracked entity was loaded before an accept/sweep flipped the row
throws `DbUpdateConcurrencyException` and answers
409 `listing_conflict` — the stale editor never silently rewrites a sold
listing (`CatalogueDbContext.cs:158-162`,
`ListingEndpoints.cs:214-221`).

## Idempotency replay semantics

Replay safety is also an integrity control, not just convenience:

- Same key + same body → 200 replay, even if catalog state drifted;
  same key + **any** difference → 409 `idempotency_key_reused` — a
  mismatched retry is a client bug worth surfacing, not a silent second
  outcome (`ListingEndpoints.cs:126-139`).
- Accept keys are scoped to the buyer: replaying listing A's key against
  listing B → 409 (`ListingEndpoints.cs:289-291`), and the DB-side unique
  `(AcceptedById, IdempotencyKey)` index is the backstop if two accepts
  race past the pre-check.

## Search escaping

The `q` parameter is escaped for `\`, `%`, `_` before being placed in the
`ILIKE` pattern (`ListingEndpoints.cs:71`), so a search term cannot inject
wildcards into the pattern (full-table scans, or trivially broad matches).
Escaping happens before interpolation into the EF `ILike` call — the
pattern is a parameter, the term is never SQL-concatenated.

## Known gaps

Open items from the 2026-09 audit, each with its anchor and the planned
fix. None of these has a test that would fail if the behavior regressed —
see the UNGUARDED registry in [testing.md](testing.md#deliberately-not-tested).

1. **JWKS refresh holds a lock across a blocking 10 s fetch, no negative
   caching** (`Program.cs:172-202`): the first validation after a cache
   miss takes `lock (Lock)` and does a synchronous HTTP fetch inside it,
   so an auth outage serializes every validating request into a queue
   behind 10 s fetches — a self-DoS. *Fix (audit rec. 7): fetch outside
   the lock; add a short negative cache so a failed fetch isn't retried
   per request.*

2. **Plaintext internal hops, `RequireHttpsMetadata = false`**
   (`Program.cs:52`; insecure gRPC channel, `Auth/Introspection.cs:29-32`):
   JWKS, introspection tokens, and the shared secret all traverse the
   compose network in cleartext, and there is no Production guard forcing
   TLS. *Fix: mTLS or an in-cluster transport guard — a fleet-level
   deferral shared with the gateway.*

3. **App clock in the expiry predicate** (`ListingEndpoints.cs:317`):
   accept's conditional UPDATE uses `DateTime.UtcNow` (app clock) while
   the ExpiryScanner uses DB `now()` (`ExpiryScanner.cs:61`). A skewed
   app server could accept a listing the DB considers lapsed (or
   vice versa). *Fix: evaluate the predicate against DB `now()` (as the
   scanner already does), together with the injectable clock below.*

4. **Unbounded `/me` reads**: `me/trades`, `me/listings`, `me/watchlist`,
   `me/item-lists` have no limit/offset (`ListingEndpoints.cs:395-419`,
   `MeEndpoints.cs:26-119`) — a user with 500 watchlist entries or a long
   trade history pulls everything per call. *Fix: clamped pagination like
   the browse endpoints.*

5. **HTTP 409 xmin mapping UNGUARDED** (`ListingEndpoints.cs:214-221`):
   the `DbUpdateConcurrencyException` → 409 translation is correct but
   no test exercises it at the HTTP layer. *Fix: persistence-level pin
   driving an HTTP-level assert.*

6. **Cross-user scoping UNGUARDED**: the ownership-in-predicate pattern
   (above) is applied everywhere but pinned by tests only for the accept
   path — PATCH-by-non-owner, watchlist scoping and trade participant
   scoping would not fail a test if a predicate lost its `AuthorId == sub`
   clause. *Fix: adversarial cross-user tests per endpoint.*

7. **Search escaping UNGUARDED** (`ListingEndpoints.cs:71`): the escaping
   is real but untested. *Fix: one-liner test asserting `%`/`_` in `q`
   match literally.*

8. **In-transaction expiry predicate blind spot** (`ListingEndpoints.cs:317`):
   fault injection (2026-09-03) proved the suite stays green if this
   predicate is deleted — the lapsed-accept test only pins the pre-check
   path. Details in [testing.md](testing.md#the-2026-09-03-fault-injection-session).
   *Fix: injectable clock + persistence-level pin, mirroring the xmin fix
   in commit `3d59490`.*

---

Related: [architecture.md](architecture.md) · [testing.md](testing.md)
