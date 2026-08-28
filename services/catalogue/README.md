# catalogue — Catalogue & Listings

The **catalogue** service owns the marketplace catalog: items, currencies,
categories, listings, offers, and trades.

- **Stack:** C# / ASP.NET Core (.NET 10), port 8081
- **Database:** PostgreSQL (`catalogue_db`)

## Current state

- Basic ASP.NET Core skeleton: `/`, `/health/live`, `/health/ready`.

## What this service will do

- Items, currencies, categories
- Listings and multi-item offers
- Trade resolution
- Search and watchlist
- Expiry scheduler for stale listings
- Publish `listing.created` / `listing.sold` / `listing.expired` events to Kafka

## Local dev

```bash
# from repo root
make postgres
make catalogue
# or: cd services/catalogue && dotnet run
```

Verify:

```bash
curl http://localhost:8081/
curl http://localhost:8081/health/live
curl http://localhost:8081/health/ready
```

Requires `DATABASE_URL` (defaults to `catalogue_db`).