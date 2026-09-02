# assets — Assets & Images

The **assets** service owns image uploads, processing, and the media
library for OpenMarket.

- **Stack:** Python / FastAPI, port 8084
- **Database:** PostgreSQL (`asset_db`)
- **Object storage:** MinIO (S3-compatible)

## Current state

- Basic FastAPI skeleton: `/`, `/health/live`, `/health/ready`.

## Planned responsibilities

- Uploads (multipart through the gateway, streamed to MinIO)
- Processing: resize, WebP conversion (Pillow), OG previews
- Media library (per-user assets, dedup by content hash)
- Serving via presigned URLs / CDN-friendly cache headers

Large-binary endpoints deliberately stay HTTP multipart end to end — see
the protocol selection rule in
[`docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md) (gRPC is for typed
sync calls, not blobs).

## Local dev

```bash
# from repo root (needs infra: make infra-up — includes MinIO)
make assets
```

Tests: `python3 -m pytest` (health smoke tests in `tests/`).
