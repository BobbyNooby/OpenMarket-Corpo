# Security

> [messaging](../README.md) › Security

How messaging decides who you are, what you may see, and where the
known gaps are. Architecture context: [architecture.md](architecture.md).

## Contents

- [Identity chain](#identity-chain)
- [Participant scoping and 404 masking](#participant-scoping-and-404-masking)
- [Cross-site WebSocket hijacking](#cross-site-websocket-hijacking)
- [Content and request limits](#content-and-request-limits)
- [Known gaps](#known-gaps)

---

## Identity chain

1. **Token extraction** — `Authorization: Bearer <jwt>` first, then the
   `om_access` cookie (`handlers.go:83-92`). The cookie fallback means
   the WS upgrade works from a browser tab without a header; it is also
   why the Origin check matters (below).
2. **Service-level verification** — `JWKSVerifier` validates RS256
   signature and expiry offline against auth's JWKS
   (`AUTH_URL/.well-known/jwks.json`, `main.go:74`):
   - `jwt.WithValidMethods([]string{"RS256"})` + `WithExpirationRequired`
     — HS256 alg-confusion and exp-less tokens are rejected
     (`jwt.go:66-68`).
   - `sub` must parse as a uuid or the token is useless (`jwt.go:75-78`).
   - **kid-keyed cache** with a 1h max-age refresh and lazy rotation:
     an unknown `kid` triggers one JWKS re-fetch — but at most once per
     30s (`minFetch`), so a flood of garbage-kid tokens costs one fetch
     per window instead of wedging auth with per-request fetches
     (`jwt.go:36-38`, `55-56`, `94-106`; audit Important fix).
3. **Edge layer (gateway)** — every messaging route and `/ws` is wrapped
   in the gateway's auth middleware: ban blocklist + token introspection
   (`gateway/internal/upstream/messaging/mount.go:35-40`). This is
   defense in depth, not the primary check: the service verifies every
   token itself, so it stays safe if reached directly on the internal
   network (`../README.md`: "Identity & auth").

Failure at any layer is a uniform 401 `{"code":"unauthorized"}` — the
service does not distinguish missing from invalid tokens in its
response (`handlers.go:71-79`).

## Participant scoping and 404 masking

Non-participant == unknown conversation, everywhere except
sender-scoped delete:

| Operation | Non-participant sees | Why |
|---|---|---|
| List messages | 404 `not_found` "Unknown conversation" (`handlers.go:243-245`; store returns `ErrNotFound` from the participant check, `store.go:196-201`) | probing conversation ids must not confirm existence |
| Send message | 404 masked, same as above (`handlers.go:287-289`, `store.go:247-252`) | same |
| Mark read | 404 masked (`handlers.go:319-321`, `store.go:290-292`) | same |
| Delete message | **403** when the message exists but isn't yours; 404 when unknown or already deleted (`handlers.go:341-345`, `store.go:296-308`) | deliberate exception: delete is sender-scoped, and the caller already knows the message id — the forbidden answer leaks nothing actionable |

This masking is v1 parity, stated as the design intent at the sentinel
errors (`store.go:12-15`) and pinned by tests
(`Test_list_messages_masks_non_participants_as_404`,
`Test_delete_message_distinguishes_forbidden_from_missing`).

## Cross-site WebSocket hijacking

The WS upgrade authenticates via the `om_access` cookie, so a malicious
page could open `wss://…/ws` with the victim's ambient cookie — unless
the origin is checked. Two independent layers:

| Layer | Mechanism | Anchor |
|---|---|---|
| Service | explicit Origin allowlist from `WS_ALLOWED_ORIGINS`; a present-but-mismatched Origin is refused 403. `CheckOrigin` is an allowlist, not `return true` — the code comment calls an unchecked Origin exactly what it is (`handlers.go:44-57`) | CSWSH guard, pinned by `Test_ws_rejects_disallowed_origins` |
| Cookie | `SameSite=Lax` on `om_access` (set by auth) keeps the cookie off most cross-site WS handshakes in modern browsers | `services/auth/src/main/java/dev/bob/openmarket/auth/token/TokenCookieService.java:73` — details in [`services/auth/docs/security.md`](../../auth/docs/security.md) |

Empty `Origin` is allowed (native clients, tests produce none) — that
is the documented trade-off; browsers always send Origin on WS, so the
browser attack surface is covered by the allowlist.

Note the gateway edge check also runs on `/ws` (it's a GET,
`mount.go:40`), so banned users can't even attempt the upgrade.

## Content and request limits

| Limit | Value | Anchor |
|---|---|---|
| Message content | ≤ 4000 chars after trim, non-empty | `handlers.go:17`, `276-284` |
| Send body | `MaxBytesReader` 4512 bytes (4000 + 512) | `handlers.go:272` |
| Create body | `MaxBytesReader` 8 KiB | `handlers.go:142` |
| WS client frames | read limit 512 bytes (push-only — any app frame is protocol misuse) | `handlers.go:379` |
| Page size | `limit` 1..100, default 50 | `handlers.go:223-231` |
| Sockets per user | 8; 9th refused with close 1013 | `hub.go:15`, `handlers.go:369-376` |

Server hardening: read-header 5s / read 30s / idle 120s timeouts, no
WriteTimeout (WS long-lived by design) (`main.go:78-85`); internal
errors are logged, never returned to the client — the body is always
the generic `"Something went wrong"` (`handlers.go:168` et al.).

## Known gaps

Honest list, each with where it lives and what's planned. Sources: the
2026-09-03 audit ([`docs/AUDIT-2026-09-03.md`](../../../docs/AUDIT-2026-09-03.md))
and the code.

| # | Gap | Detail | Planned fix |
|---|---|---|---|
| 1 | **PostgresStore SQL is UNGUARDED at the persistence level** — top debt | handler tests run on a fake `Store`; the participant-scoping SQL, the advisory-lock pair-create serialization, and the cursor query have no test against a real Postgres. A SQL regression in scoping would silently reopen the 404-masking hole | Testcontainers-style integration tests mirroring catalogue's `CatalogueFixture`, env-guarded so CI's docker-less lanes still pass — plan tracked in [testing.md](testing.md#deliberately-not-tested-yet) |
| 2 | Single-instance hub = in-memory state lost on restart | live sockets are per-process (`hub.go:34-37`); a restart silently drops all pushes until clients reconnect, and horizontal scale is impossible without shared fan-out | Redis fan-out, deferred with the events phase (`../README.md`) |
| 3 | Stale-ban redelivery nuance (gateway-side) | the ban blocklist consumer is at-least-once — a failed commit means the event is redelivered (`gateway/internal/blocklist/consumer.go:39`) — so a banned user's edge rejection can lag briefly. Not a messaging bug, but it sets the rule for the future `message.created` consumers: **event handlers must be idempotent**, because at-least-once delivery is the fleet-wide Kafka posture | idempotency is a design requirement for the deferred messaging producer/consumer pair |
| 4 | No per-user or per-IP rate limiting in the service | the only rate-shaped controls are the socket cap and request body limits; a flood of message sends is bounded only by gateway policy and Postgres | not yet planned; revisit with presence/events |

<!-- unverified: whether gateway rate-limiting exists upstream of messaging — documented as gap #4 precisely because this session verified only the socket cap and body limits in the service itself. -->
