-- OpenMarket v2 — auth service schema.
-- Lives in the `auth` schema everywhere (DB-per-service locally, schema-per-service on Supabase).
-- Tables use UUID PKs, audit timestamps, and soft deletes (deleted_at).

CREATE SCHEMA IF NOT EXISTS auth;

-- ── users ────────────────────────────────────────────────
CREATE TABLE auth.users (
    id             UUID PRIMARY KEY,
    email          TEXT NOT NULL UNIQUE,
    name           TEXT NOT NULL,
    avatar_url     TEXT,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at     TIMESTAMPTZ
);

-- ── user_profiles ────────────────────────────────────────
CREATE TABLE auth.user_profiles (
    user_id                  UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    username                 TEXT NOT NULL UNIQUE,
    bio                      TEXT,
    social_links             TEXT,
    accent_color             TEXT,
    language                 TEXT NOT NULL DEFAULT 'en',
    notification_preferences TEXT NOT NULL DEFAULT '{}',
    avatar_url               TEXT
);

-- ── users_activity ───────────────────────────────────────
CREATE TABLE auth.users_activity (
    user_id          UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    is_active        BOOLEAN NOT NULL DEFAULT FALSE,
    last_activity_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── credentials (email + password only) ──────────────────
CREATE TABLE auth.credentials (
    user_id       UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    password_hash TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── oauth_accounts (Discord, ...) ────────────────────────
CREATE TABLE auth.oauth_accounts (
    id                  UUID PRIMARY KEY,
    user_id             UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    provider            TEXT NOT NULL,
    provider_account_id TEXT NOT NULL,
    access_token        TEXT,
    refresh_token       TEXT,
    token_expires_at    TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (provider, provider_account_id)
);

-- ── refresh_tokens (opaque, stored hashed) ───────────────
-- Tokens rotate on every use. rotation = same family_id, new row,
-- old row's revoked_at set. Presenting an already-revoked token is
-- theft evidence → the whole family is revoked.
CREATE TABLE auth.refresh_tokens (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    token_hash      TEXT NOT NULL UNIQUE,
    family_id       UUID NOT NULL,
    rotated_from_id UUID REFERENCES auth.refresh_tokens(id) ON DELETE SET NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_refresh_tokens_user ON auth.refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_family ON auth.refresh_tokens(family_id);
CREATE INDEX idx_refresh_tokens_user_active ON auth.refresh_tokens(user_id) WHERE revoked_at IS NULL;

-- ── verification_tokens (email verify / password reset) ──
CREATE TABLE auth.verification_tokens (
    id         UUID PRIMARY KEY,
    user_id    UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    type       TEXT NOT NULL,
    token_hash TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_verification_tokens_hash ON auth.verification_tokens(token_hash);

-- ── RBAC ─────────────────────────────────────────────────
CREATE TABLE auth.roles (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL UNIQUE,
    description TEXT
);

CREATE TABLE auth.permissions (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL UNIQUE,
    description TEXT
);

CREATE TABLE auth.role_permissions (
    id            UUID PRIMARY KEY,
    role_id       TEXT NOT NULL REFERENCES auth.roles(id) ON DELETE CASCADE,
    permission_id TEXT NOT NULL REFERENCES auth.permissions(id) ON DELETE CASCADE,
    UNIQUE (role_id, permission_id)
);

CREATE TABLE auth.user_roles (
    id      UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    role_id TEXT NOT NULL REFERENCES auth.roles(id) ON DELETE CASCADE,
    UNIQUE (user_id, role_id)
);

-- ── seeds ────────────────────────────────────────────────
INSERT INTO auth.roles (id, name, description) VALUES
    ('user',      'User',      'Default registered user'),
    ('moderator', 'Moderator', 'Can moderate listings, reviews and reports'),
    ('admin',     'Admin',     'Full management access'),
    ('owner',     'Owner',     'Platform owner / superuser')
ON CONFLICT (id) DO NOTHING;

INSERT INTO auth.permissions (id, name, description) VALUES
    ('auth.user.read',          'Read own profile',              'User can read their own profile'),
    ('auth.user.write',         'Update own profile',            'User can update their own profile'),
    ('catalogue.listing.read',  'Read listings',                 'User can browse listings'),
    ('catalogue.listing.write', 'Create listings',               'User can create and manage their own listings'),
    ('messaging.message.read',  'Read messages',                 'User can read their conversations'),
    ('messaging.message.write', 'Send messages',                 'User can send messages'),
    ('catalogue.listing.moderate', 'Moderate listings',          'Moderator can hide or remove listings'),
    ('admin.user.warn',         'Warn users',                    'Moderator can issue warnings'),
    ('admin.report.read',       'Read reports',                  'Moderator can view reports'),
    ('admin.report.resolve',    'Resolve reports',               'Moderator can resolve reports'),
    ('admin.user.ban',          'Ban users',                     'Admin can ban users'),
    ('admin.audit.read',        'Read audit log',                'Admin can view the audit log'),
    ('admin.site.config',       'Manage site config',            'Admin can change site configuration'),
    ('admin.analytics.read',    'Read analytics',                'Admin can view analytics')
ON CONFLICT (id) DO NOTHING;

INSERT INTO auth.role_permissions (id, role_id, permission_id) VALUES
    (gen_random_uuid(), 'user', 'auth.user.read'),
    (gen_random_uuid(), 'user', 'auth.user.write'),
    (gen_random_uuid(), 'user', 'catalogue.listing.read'),
    (gen_random_uuid(), 'user', 'catalogue.listing.write'),
    (gen_random_uuid(), 'user', 'messaging.message.read'),
    (gen_random_uuid(), 'user', 'messaging.message.write'),
    (gen_random_uuid(), 'moderator', 'catalogue.listing.moderate'),
    (gen_random_uuid(), 'moderator', 'admin.user.warn'),
    (gen_random_uuid(), 'moderator', 'admin.report.read'),
    (gen_random_uuid(), 'moderator', 'admin.report.resolve'),
    (gen_random_uuid(), 'admin', 'admin.user.ban'),
    (gen_random_uuid(), 'admin', 'admin.audit.read'),
    (gen_random_uuid(), 'admin', 'admin.site.config'),
    (gen_random_uuid(), 'admin', 'admin.analytics.read'),
    (gen_random_uuid(), 'owner', 'admin.user.ban'),
    (gen_random_uuid(), 'owner', 'admin.audit.read'),
    (gen_random_uuid(), 'owner', 'admin.site.config'),
    (gen_random_uuid(), 'owner', 'admin.analytics.read')
ON CONFLICT DO NOTHING;