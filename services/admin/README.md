# admin — Admin & Moderation

The **admin** service owns the moderation back-office: reports, the platform
audit log, site configuration, and admin-facing analytics.

- **Stack:** Java / Spring Boot, port 8085
- **Database:** PostgreSQL (`admin_db`)

## Current state

- Basic Spring Boot skeleton: `/`, `/health/live`, `/health/ready`.
- Dockerfile + compose wiring, same shape as the auth service.

## Planned responsibilities

- Reports: CRUD, triage, resolve (fed by `report.created` events)
- Site-wide audit log (beyond auth's user-scoped one) and admin audit reads
- Site config / theming
- Analytics ingestion + admin insights

Note: **user moderation lives in the auth service today** (bans, warnings,
role management at `/api/v1/admin/users*`, backed by `audit_log`). When this
service takes over the admin surface, those routes move and the gateway's
`/api/v1/admin` mount re-points here — see the gateway's
[routing docs](../gateway/docs/routing.md).

## Local dev

```bash
# from repo root (needs infra: make infra-up)
make admin
```
