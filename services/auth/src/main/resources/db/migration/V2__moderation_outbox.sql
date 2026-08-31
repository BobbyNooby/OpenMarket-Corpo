-- OpenMarket v2 — auth service schema, batch 1 additions.
-- moderation tables for Phase E (unused until then), Kafka outbox, and
-- device metadata on refresh tokens (feeds GET /api/v1/auth/sessions).

-- ── refresh_tokens: remember the device that owns the session ──
ALTER TABLE auth.refresh_tokens ADD COLUMN IF NOT EXISTS user_agent TEXT;
ALTER TABLE auth.refresh_tokens ADD COLUMN IF NOT EXISTS ip_address TEXT;

-- ── user_bans (Phase E: enforced at login/refresh + gateway blocklist) ──
CREATE TABLE auth.user_bans (
    id        UUID PRIMARY KEY,
    user_id   UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    banned_by UUID REFERENCES auth.users(id) ON DELETE SET NULL,
    reason    TEXT,
    banned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ,
    lifted_at  TIMESTAMPTZ
);
CREATE INDEX idx_user_bans_user_active ON auth.user_bans(user_id) WHERE lifted_at IS NULL;

-- ── user_warnings (Phase E) ──
CREATE TABLE auth.user_warnings (
    id        UUID PRIMARY KEY,
    user_id   UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    warned_by UUID REFERENCES auth.users(id) ON DELETE SET NULL,
    reason    TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_user_warnings_user ON auth.user_warnings(user_id);

-- ── outbox_events (atomic DB-write + future Kafka publish) ──
CREATE TABLE auth.outbox_events (
    id             UUID PRIMARY KEY,
    aggregate_type TEXT NOT NULL,
    aggregate_id   UUID NOT NULL,
    topic          TEXT NOT NULL,
    payload        JSONB NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at   TIMESTAMPTZ
);
CREATE INDEX idx_outbox_pending ON auth.outbox_events(created_at) WHERE published_at IS NULL;
