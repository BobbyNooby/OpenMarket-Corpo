# Architecture

> [auth](../README.md) › Architecture

How this service fits into the OpenMarket v2 fleet, and where trust lives.

## Contents

- [Position in the fleet](#position-in-the-fleet)
- [Trust model](#trust-model)
- [Request path](#request-path)
- [Deployment shape](#deployment-shape)

---

## Position in the fleet

The auth service is one of seven services in the polyglot v2 rebuild. It
owns identity and nothing else — listings, chat, notifications etc. only
ever see opaque user ids (uuids) and verified JWT claims.

```
                    ┌──────────────┐
   SvelteKit  ─────▶│ Go gateway   │◀──── the ONLY public entry (:3000)
   frontend         │  (BFF)       │
                    └──────┬───────┘
                           │ validates JWT locally using
                           │ GET /.well-known/jwks.json (cached, max-age=900)
                           ▼
                    ┌──────────────┐
                    │ auth :8080   │──▶ auth_db (Postgres, schema "auth")
                    └──────────────┘
```

- The frontend never talks to auth directly. The gateway proxies
  `/api/v1/auth/*` and `/api/v1/users/*`.
- Auth is **not on the hot path** of ordinary requests: the gateway verifies
  JWT signatures itself using this service's published public keys.

## Trust model

| Actor | What it can do | What it must not assume |
|---|---|---|
| Browser | presents cookies | nothing — zero trust, tokens can be stolen (hence rotation + theft detection) |
| Gateway | verifies JWTs offline via JWKS | that a non-revoked token means a non-banned user (ban check = Redis blocklist, Phase E) |
| Other services | receive `Authorization: Bearer …` or identity headers from the gateway | nothing about users except what the claims say; richer data comes from auth endpoints or Kafka events |
| Auth | sole authority on identity, passwords, OAuth links, roles | — |

## Request path

1. Browser sends cookies (`om_access`, `om_refresh`) to the gateway.
2. Gateway reads `om_access`, verifies signature + expiry + `iss`/`aud`
   against the cached JWKS, and forwards `Authorization: Bearer <jwt>` to
   the target service.
3. The service (or auth itself) re-verifies with the same public key and
   maps the `roles` claim to authorities.
4. Expired access token? The frontend hits `POST /api/v1/auth/refresh`
   through the gateway — which rotates the refresh cookie — and retries.

## Deployment shape

- **Database:** Postgres `auth_db`, everything in schema `auth`
  (db-per-service locally, schema-per-service if we ever land on
  Supabase-style single-DB hosting — `DatabaseConfig` already handles
  `DATABASE_URL` + `sslmode=require` for remote hosts).
- **Stateless:** no HTTP sessions, no server-side access-token state. Any
  number of replicas can serve any request; the only shared state is
  Postgres (refresh tokens, users) and the signing key file.
- **Scaling the signing key:** one key file today; `kid` support means
  multiple JWKS entries (multi-key rotation) is a config change, not a
  redesign.

---

Related: [tokens.md](tokens.md) · [configuration.md](configuration.md) ·
fleet-wide decisions in [`docs/ARCHITECTURE.md`](../../../docs/ARCHITECTURE.md)
