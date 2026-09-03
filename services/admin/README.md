# admin — Admin & Moderation

The **admin** service owns the moderation back-office: reports, the platform
audit log, site configuration, and admin-facing analytics.

- **Stack:** Java / Spring Boot, port 8085
- **Database:** PostgreSQL (`admin_db`)

## Current state

- Basic Spring Boot skeleton: `/`, `/health/live` (dependency-free),
  `/health/ready` (SELECT 1 against Postgres, fixed-string error on
  failure — no exception leakage).
- Flyway baseline migration (`V1__baseline.sql`: reports, audit_log,
  site_config, analytics_events) so first boot has schema history;
  `ddl-auto: validate` meets it once Phase 3 entities land.
- Spring Boot 4.1.1 (migrated off the 3.3.4 EOL line to match auth),
  non-root container image, EXPOSE 8085.

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
