-- OpenMarket v2 — auth service, stop persisting Discord provider tokens.
-- The access/refresh token columns on oauth_accounts were write-only (never
-- read back); the provider token is used transiently during the OAuth
-- callback and dropped afterwards.

ALTER TABLE auth.oauth_accounts DROP COLUMN IF EXISTS access_token;
ALTER TABLE auth.oauth_accounts DROP COLUMN IF EXISTS refresh_token;
ALTER TABLE auth.oauth_accounts DROP COLUMN IF EXISTS token_expires_at;
