# auth — Auth & Users

The **auth** microservice owns identity for OpenMarket: user registration,
authentication, token lifecycle, and profile management.

- **Stack:** Java 21, Spring Boot 3.3.4, Maven
- **Database:** PostgreSQL (`auth_db`)
- **Cache / Tokens:** Redis (refresh tokens, rate limits)
- **Messaging:** Kafka (user lifecycle events)
- **Port:** 8080

## Current state

- Spring Boot app boots and connects to Postgres via HikariCP.
- `/` returns service info and pings the database (`SELECT version()`).
- Flyway is wired up and ready for migrations.

## What this service will do

- Register and authenticate users
- Issue short-lived JWT access tokens + opaque refresh tokens
- Validate JWTs (public key published for the gateway)
- Rotate and revoke refresh tokens
- Manage user profiles and account status
- Publish user lifecycle events to Kafka
- Provide admin endpoints for moderation

## Token strategy

| Token | Type | Storage | Lifetime |
|-------|------|---------|----------|
| Access token | Signed JWT | Client header/cookie | 15 minutes |
| Refresh token | Opaque random string | Server-side, hashed | 7–30 days, rotated on use |

The gateway validates JWTs locally using the auth service's public key so
auth isn't called on every request.

## Roadmap

### Phase 1 — Foundations
- User + credentials tables with UUIDs, audit fields, soft deletes
- User entity, repository, service, controller
- Global exception handler + standard error envelope
- Input validation + OpenAPI docs

### Phase 2 — Security
- Password hashing (BCrypt/Argon2id)
- Register, login, logout endpoints
- JWT issue/verify with RS256
- Refresh token rotation + revocation
- Rate limiting + account lockout
- Email verification + password reset

### Phase 3 — Enterprise features
- RBAC with roles + permissions
- Admin endpoints for moderation
- Audit log for auth events
- Kafka outbox for reliable user events
- GDPR export + erasure
- MFA hooks (future)

### Phase 4 — Observability
- Structured logging with correlation IDs
- Micrometer metrics
- OpenTelemetry tracing
- Custom health indicators

## API versioning

Routes are versioned in the URL (`/api/v1/...`). This keeps versions explicit,
cacheable, and easy to route in the gateway.

When a breaking change is needed:
1. Copy the controller / DTOs to a `v2` package.
2. Keep `v1` running for existing clients.
3. Gateway routes clients based on an `Accept-Version` header or URL path.
4. Deprecate `v1` before removal.

## API surface (v1)

### Public

- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`
- `POST /api/v1/auth/verify-email`
- `POST /api/v1/auth/forgot-password`
- `POST /api/v1/auth/reset-password`

### Authenticated

- `GET /api/v1/users/me`
- `PATCH /api/v1/users/me`
- `DELETE /api/v1/users/me`

### Admin

- `GET /api/v1/admin/users`
- `GET /api/v1/admin/users/{id}`
- `PATCH /api/v1/admin/users/{id}/status`
- `POST /api/v1/admin/users/{id}/erase`
- `GET /api/v1/admin/users/{id}/export`

## Local dev

```bash
# from repo root
make postgres      # starts Postgres
make auth          # runs the service
```

Verify:

```bash
curl http://localhost:8080/
curl http://localhost:8080/actuator/health
```

## Open decisions

- JWT signing: shared HMAC vs RSA keypair?
- Refresh token storage: Postgres vs Redis?
- Email delivery provider and local dev fallback
- GDPR erasure: immediate or scheduled?
- Multi-tenancy: single-tenant marketplace for now?
