# auth — Auth & Users

The **auth** microservice owns identity for OpenMarket: user registration,
authentication, token lifecycle, and profile management. It is the only
service that knows what a password or a Discord account is — every other
service just consumes JWTs.

- **Stack:** Java 21, Spring Boot 3.3.4, Maven, Spring Security (resource server)
- **Database:** PostgreSQL `auth_db`, schema `auth` (Flyway migrations)
- **Port:** 8080
- **Status:** **Phases A–E done** — schema, password auth, RS256 JWT with
  rotation + theft detection, session/device management, profile CRUD,
  Discord OAuth + account linking, email flows (verify / change / reset
  with rate limiting), and RBAC moderation (ban / warn / roles / erase,
  owner ⊃ admin ⊃ moderator) — pinned by **140 tests** plus the live
  **flow test** (`scripts/flow-test.sh`, 108 assertions through a fake
  Discord API and the dev mail log).

## Quick start

```bash
# from repo root
make postgres      # Postgres on :5432 (auth_db auto-created)
make auth          # Spring Boot on :8080
make test          # or: cd services/auth && mvn test  (140 tests)
services/auth/scripts/flow-test.sh   # full live walkthrough incl. fake Discord

curl http://localhost:8080/                      # service info
curl http://localhost:8080/health/ready          # checks Postgres
open http://localhost:8080/docs                  # Swagger UI
```

First boot: Flyway creates + seeds the schema, and an RSA signing key is
**auto-generated** into `services/auth/keys/` (gitignored). Try:

```bash
curl -c jar.txt -X POST localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"me@x.dev","password":"supersecret1","name":"Bomo"}'
curl -b jar.txt localhost:8080/api/v1/users/me
```

## The 60-second version

| Question | Answer | Details |
|---|---|---|
| How does login work? | 15-min RS256 JWT in an httpOnly cookie + 7-day opaque refresh token (hash-only in Postgres), rotated on every use | [tokens](docs/tokens.md) |
| What happens on cookie theft? | Replay of a consumed refresh token revokes the **whole session family** | [rotation & theft detection](docs/tokens.md#refresh-rotation--theft-detection) |
| Where am I logged in? | `GET /auth/sessions` — live device list; revoke one or all | [api](docs/api.md#2-live-endpoints) |
| How does Discord sign-in work? | Authorization-code flow with a signed state cookie; verified emails auto-link — one identity, many login methods | [accounts](docs/accounts.md) |
| Who verifies JWTs? | The Go gateway, offline, via `/.well-known/jwks.json` — auth is not on the hot path | [architecture](docs/architecture.md) |
| What's in the database? | 14 tables in schema `auth` — migrations V1 + V2 | [data model](docs/data-model.md) |
| Is it tested? | 140 contract/unit tests + a live flow test (fake Discord + mail log) | [testing](docs/testing.md) |
| Is it secure? | BCrypt-12, no user enumeration, HMAC-bound OAuth state, httpOnly+Lax cookies; known gaps listed honestly | [security](docs/security.md) |
| Which env vars do I need? | None locally; the full table with why/what-breaks is in the config doc | [configuration](docs/configuration.md) |
| What's next? | Email flows → RBAC admin (contract frozen in api.md) | [roadmap](docs/roadmap.md) |

## Documentation

Full docs live in **[docs/](docs/README.md)** (with their own index):

1. [Architecture](docs/architecture.md) — fleet position, trust model
2. [Tokens](docs/tokens.md) — strategy, claims, rotation
3. [Accounts & linking](docs/accounts.md) — passwords vs OAuth
4. [Data model](docs/data-model.md) — tables + conventions
5. [API reference](docs/api.md) — live + designed endpoints, error codes
6. [Configuration & keys](docs/configuration.md) — env vars explained, per-env setups
7. [Testing](docs/testing.md) — the suite, what each test pins, how to extend
8. [Security notes](docs/security.md) — posture + known gaps
9. [Roadmap](docs/roadmap.md) — phases A–E

Repo-wide: [`ARCHITECTURE.md`](../../docs/ARCHITECTURE.md) ·
[`V1-SCHEMAS.md`](../../docs/V1-SCHEMAS.md) · [`DEV.md`](../../DEV.md)
