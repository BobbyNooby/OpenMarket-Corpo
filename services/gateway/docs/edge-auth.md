# Edge authentication

> [gateway](../README.md) › Edge auth

Every protected request is authenticated at the edge via the auth service's
`IntrospectToken` gRPC (`auth.v1`). This page explains the semantics, the
cache, and the one window worth knowing about.

## The decision matrix

| Request | Edge behavior | Why |
|---|---|---|
| Public path (exact-match list) | pass through, no token work | auth's `permitAll` mirrored 1:1 — no prefix leakage into subpaths |
| Protected path, **no token** | forward anyway | the upstream owns the 401 envelope; one source of truth for error shape |
| Protected path, token **inactive** | `401 unauthorized` at the edge | invalid, expired, **soft-deleted**, or **banned** — no reason to burn an upstream hop |
| Protected path, introspection **unavailable** | `503 service_unavailable` | fail closed: a flaky check must never degrade into "allow" |

Token extraction mirrors auth's own resolver: `Authorization: Bearer` wins,
`om_access` cookie is the fallback. The original token is always forwarded
untouched — upstreams re-validate; the edge is the fast no, never the only
check.

## Why introspection is more than a signature check

A local JWT validation answers "was this token signed by auth and is it
unexpired?" It cannot see database state. `IntrospectToken` answers from the
database: soft-deleted and banned accounts report `active=false` while their
token is still cryptographically perfect. That difference is the entire
product — see the contract's doc comment in
[`contracts/proto/openmarket/auth/v1/auth.proto`](../../../contracts/proto/openmarket/auth/v1/auth.proto).

Error semantics follow RFC 7662-style introspection: bad/expired/garbage
tokens get `active=false`; infrastructure trouble (DB down, malformed
request) surfaces as a gRPC error so the gateway can 503 instead of 401.

## The verdict cache

| Property | Value | Rationale |
|---|---|---|
| Key | SHA-256(token) | no plaintext tokens in memory |
| TTL | 10s | ban/delete propagation is bounded by this, not by token TTL |
| Cap | 10,000 entries | flood-safe; expired sweep then arbitrary eviction |
| Caches | responses only | outages stay fail-closed — errors are never cached |

Without the cache, every token-bearing request is one RSA-2048 verify plus
up to three auth DB queries — a single valid token amplifies into an auth
load generator through any protected route. Found by the recursion-2 audit;
the cache was the fix, and the routing test pins it (`introspected == 1` for
two same-token requests).

### The ban-propagation window

Two layers, two clocks:

1. **Blocklist (event-driven, ~seconds)** — auth's outbox relay publishes
   `user.banned`; the gateway's `gateway-blocklist` consumer writes Redis;
   the edge check answers 401 for blocked subs *before* introspection. A
   Redis outage fails open into layer 2 (the blocklist is an optimization,
   never the authority).
2. **Introspection cache (≤10s TTL)** — auth reports `active=false` for
   banned users, so even a blocklist miss dies at the edge within one cache
   TTL. Pinned end-to-end by flow-test §14's *banned user's pre-ban token*
   step: removing the edge middleware turns that step red.

## Connection lifecycle

- `authclient.Dial` polls `grpc.health.v1` for up to 10s at boot so a cold
  auth container doesn't turn the first requests into 503s.
- The wait is **non-fatal**: an unreachable auth degrades protected routes
  (503) and REST hops (502) but never takes the gateway down — health,
  stubs, and unknown-route 404s keep serving.
- Per-call deadline is 1s (`WaitForReady` off), so an auth outage surfaces
  as fast 503s, not pileups.
