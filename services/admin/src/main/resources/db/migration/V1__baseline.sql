-- admin_db baseline: the moderation & platform-admin domain, so Flyway has
-- a history from first boot and ddl-auto:validate has a schema to meet.
-- Phase 3 (reports/audit/theme/analytics features) evolves from here.

CREATE TABLE reports (
    id          UUID PRIMARY KEY,
    reporter_id UUID NOT NULL,
    target_type VARCHAR(32)  NOT NULL, -- user | listing | message | ...
    target_id   UUID         NOT NULL,
    reason      VARCHAR(64)  NOT NULL,
    details     TEXT,
    status      VARCHAR(16)  NOT NULL DEFAULT 'open', -- open | resolved | dismissed
    resolved_by UUID,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_reports_status ON reports (status);

CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    actor_id    UUID,
    action      VARCHAR(64)  NOT NULL,
    target_id   UUID,
    details     JSONB,
    ip          INET,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE site_config (
    key        VARCHAR(64) PRIMARY KEY,
    value      JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE analytics_events (
    id         BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    user_id    UUID,
    payload    JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_analytics_type_time ON analytics_events (event_type, created_at);
