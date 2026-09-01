-- OpenMarket v2 — auth service schema, Phase E additions.
-- Admin audit trail (who did what to whom) plus the performance and
-- integrity indexes the moderation/RBAC hot paths were missing.

-- ── audit_log (admin actions; written in the same tx as the change) ──
CREATE TABLE auth.audit_log (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id       UUID,
    action         TEXT NOT NULL,
    target_user_id UUID,
    details        JSONB,
    ip             TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_audit_log_target ON auth.audit_log(target_user_id);
CREATE INDEX idx_audit_log_created ON auth.audit_log(created_at);

-- ── performance / integrity indexes ──────────────────────
CREATE INDEX idx_oauth_accounts_user ON auth.oauth_accounts(user_id);
CREATE INDEX idx_user_roles_role ON auth.user_roles(role_id);
CREATE UNIQUE INDEX uq_verification_tokens_hash ON auth.verification_tokens(token_hash);
