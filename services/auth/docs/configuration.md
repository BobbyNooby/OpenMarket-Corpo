# Configuration & keys

> [auth](../README.md) › Configuration & keys

Every knob the service has: what it does, what happens without it, and
ready-made setups per environment.

## Contents

- [Environment variables](#environment-variables)
- [Per-environment setups](#per-environment-setups)
- [application.yml reference](#applicationyml-reference)
- [Key management (RS256)](#key-management-rs256)
- [Secret hygiene](#secret-hygiene)

---

## Environment variables

### Database — *required (one way or another)*

The service needs Postgres credentials. Two mutually exclusive styles; if
`DATABASE_URL` is set and non-empty it wins, otherwise the discrete
`POSTGRES_*` vars are used.

| Var | Required | Default | What it does | What breaks without it |
|---|---|---|---|---|
| `DATABASE_URL` | one of two | *(unset)* | libpq-style `postgres://user:pass@host:port/db`. The db name in the URL is used as-is, but `currentSchema=auth` is always appended, so one shared Postgres still behaves db-per-service. Any non-localhost host gets `sslmode=require` | nothing — fallback kicks in |
| `POSTGRES_HOST` | one of two | `localhost` | Fallback host | app fails at boot (Flyway can't connect) |
| `POSTGRES_PORT` | one of two | `5432` | Fallback port | same |
| `POSTGRES_USER` | one of two | `om` | Fallback user | same |
| `POSTGRES_PASSWORD` | one of two | `devpassword123` | Fallback password — **dev-only default, never acceptable in prod** | same |

Why two styles: local dev uses the discrete vars (`make postgres` + `make auth`
need zero config), while Docker/Supabase-style hosts naturally hand you a
single URL — compose already passes `DATABASE_URL`.

### JWT — *has safe dev defaults*

| Var | Required | Default | What it does | What breaks without it |
|---|---|---|---|---|
| `JWT_KEY_PATH` | no | `keys/jwt-rsa.jwk` | Where the RS256 signing key lives (relative to the service dir). Missing file → **auto-generated on first boot** and persisted | nothing in dev; in prod a fresh key would silently invalidate all outstanding access tokens — always mount one (see [key management](#key-management-rs256)) |

Related yml (not env, but same family): `jwt.issuer` (`auth`), `jwt.audience`
(`openmarket`), `jwt.access-ttl-minutes` (15), `jwt.refresh-ttl-days` (7).
**If you change issuer/audience you must change them at the gateway too** —
that's a fleet-wide contract, not a local knob.

### Cookies — *must change outside localhost*

| Var | Required | Default | What it does | What breaks without it |
|---|---|---|---|---|
| `AUTH_COOKIE_SECURE` | no | `false` | Sets the `Secure` flag on `om_access`/`om_refresh` (browsers then only send them over HTTPS) | `false` behind real HTTPS is a security finding — cookies would also travel over plain HTTP if ever downgraded. Set `true` in staging/prod |

### Discord OAuth — *live (Phase C)*

| Var | Required | Default | What it does | What breaks without it |
|---|---|---|---|---|
| `DISCORD_CLIENT_ID` / `DISCORD_CLIENT_SECRET` | **yes for OAuth** | *(empty)* | Credentials from the [Discord developer portal](https://discord.com/developers/applications) (register the redirect URI below on the app) | `/auth/discord` flows fail at Discord's consent screen |
| `DISCORD_REDIRECT_URI` | no | `http://localhost:3000/api/v1/auth/discord/callback` | Where Discord sends the browser back — **the gateway** (only public entry), which proxies to auth. Must match a URI registered in the Discord app | Discord rejects the flow with redirect mismatch |
| `DISCORD_AUTHORIZE_URL` | no | `https://discord.com/oauth2/authorize` | Consent-screen URL. Point at the fake Discord (`scripts/fake-discord.py`) for local flow testing | — |
| `DISCORD_TOKEN_URL` | no | `https://discord.com/api/oauth2/token` | Code-exchange endpoint (form-urlencoded per Discord's spec) | — |
| `DISCORD_USERS_ME_URL` | no | `https://discord.com/api/users/@me` | Profile endpoint (`identify email` scopes) | — |
| `DISCORD_SCOPES` | no | `identify email` | `email` scope is what makes verified-email auto-linking possible | without it: no email → `oauth_email_required` for new users |
| `OAUTH_SUCCESS_REDIRECT` | no | `http://localhost:3000/auth/success` | Frontend page after a successful OAuth round-trip | — |
| `OAUTH_FAILURE_REDIRECT` | no | `http://localhost:3000/auth/failure` | Frontend page on OAuth failure — receives `?error=<code>` | — |

### Email (Phase D, live)

| Var | Required | Default | What it does | What breaks without it |
|---|---|---|---|---|
| `MAIL_HOST` | no | *(empty)* | SMTP host. **Empty = dev mode: full mail bodies printed to the app log** (the flow test greps them to complete email flows) | email flows still work, mail is just logged |
| `MAIL_PORT` | no | `1025` | SMTP port (Mailpit's default) | — |
| `MAIL_FROM` | no | `OpenMarket <no-reply@openmarket.dev>` | From header on outgoing mail | — |
| `APP_URL` | no | `http://localhost:3000` | Frontend base URL used inside emailed links (gateway origin, never auth :8080) | links point to the wrong place |

Verification token TTLs live in `application.yml`:
`auth.verification.email-verify-hours: 24`,
`auth.verification.password-reset-minutes: 60`.

### Platform (already standard in the repo)

| Var | Default | Purpose |
|---|---|---|
| `KAFKA_BROKERS` | `kafka:9092` | Unused today; reserved for the outbox relay + `user.*` events |
| `OTLP_ENDPOINT` | `http://jaeger:4318` | OpenTelemetry tracing export |

## Per-environment setups

### Local dev (zero config beyond `make postgres`)

```bash
# .env — all defaults work; this is the whole file
POSTGRES_USER=om
POSTGRES_PASSWORD=devpassword123
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
```

Nothing else needed: DB falls back to the discrete vars, a signing key is
auto-generated into `services/auth/keys/`, cookies are `Secure=false`
because localhost is not HTTPS. Password auth works out of the box; Discord
OAuth needs the vars above (or run the full flow against the fake Discord:
`services/auth/scripts/flow-test.sh`).

### Docker compose

The compose file passes `DATABASE_URL=postgres://om:…@postgres:5432/auth_db`
to the auth container — one var instead of four, same schema behaviour.
Still dev-grade secrets.

### Staging / production (behind HTTPS + gateway)

```bash
DATABASE_URL=postgres://auth_user:<secret>@db.internal:5432/auth_db
AUTH_COOKIE_SECURE=true
JWT_KEY_PATH=/etc/openmarket/auth/jwt-rsa.jwk   # mounted from the secret manager
DISCORD_CLIENT_ID=<id>                          # when Phase C ships
DISCORD_CLIENT_SECRET=<secret>
```

Checklist:

- [ ] real DB credentials (never the dev default password)
- [ ] `AUTH_COOKIE_SECURE=true`
- [ ] signing key mounted, not auto-generated
- [ ] `sslmode` satisfied (auto for non-localhost `DATABASE_URL`)
- [ ] OAuth redirect URLs point at the public gateway origin

### Supabase-style single-DB hosts

```bash
DATABASE_URL=postgres://user:pass@aws-0-eu-central-1.pooler.supabase.com:5432/postgres
```

Works as-is: the schema `auth` isolates this service's tables inside the
shared database (that's why everything is schema-qualified, not db-qualified).

## application.yml reference

| Key | Default | Purpose |
|---|---|---|
| `jwt.issuer` | `auth` | `iss` claim + decoder validation |
| `jwt.audience` | `openmarket` | `aud` claim + decoder validation |
| `jwt.access-ttl-minutes` | `15` | Access token lifetime (and cookie max-age) |
| `jwt.refresh-ttl-days` | `7` | Refresh token lifetime (and cookie max-age) |
| `jwt.key-path` | env `JWT_KEY_PATH` | Signing key location |
| `auth.cookie-secure` | env `AUTH_COOKIE_SECURE` | Cookie `Secure` flag |
| `spring.flyway.schemas` | `auth` | Migrations own this schema (+ `create-schemas: true`) |
| `spring.jpa.hibernate.ddl-auto` | `validate` | Entities must match migrations — Flyway is the source of truth |
| `springdoc.swagger-ui.path` | `/docs` | Swagger UI |

## Key management (RS256)

- **Dev:** on boot, if `JWT_KEY_PATH` doesn't exist, a 2048-bit keypair is
  generated and persisted. Restarts keep the same key → the gateway's cached
  JWKS stays valid. The file contains the **private** key and is gitignored
  (`services/auth/keys/`). Never copy it anywhere.
- **Prod:** mount a real key file at `JWT_KEY_PATH` (secret manager →
  volume). Regenerating invalidates every outstanding access token — they
  all die within 15 min; that's the whole recovery story.
- **Rotation:** the JWT header carries `kid` matching the JWKS entry.
  Multi-key rotation (verify with old + new, issue with new) is a config
  change — the `JWKSource` can hold several keys.

## Secret hygiene

- `.env` is gitignored; defaults in it are dev-only.
- The signing key file is gitignored and must never leave the machine/volume
  it was generated for.
- Logs never contain tokens or hashes; the error envelope never leaks
  internals (`internal_error` is deliberately vague — details go to the log).
- bcrypt/scrypt hashes and SHA-256 token hashes are one-way: a DB leak does
  not expose passwords or usable refresh tokens (see [security.md](security.md)).

---

Related: [architecture.md](architecture.md) · [security.md](security.md) ·
repo-wide vars in [`DEV.md`](../../../DEV.md)
