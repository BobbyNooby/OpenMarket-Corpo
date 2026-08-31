-- OpenMarket v2 — auth service schema, Phase D additions.
-- verification_tokens.identifier carries the address a token acts on:
--   email_verify  -> the address being verified (== users.email)
--   email_change  -> the NEW address that becomes users.email on confirm
--   password_reset-> the address being recovered (informational)

ALTER TABLE auth.verification_tokens ADD COLUMN IF NOT EXISTS identifier TEXT;
CREATE INDEX IF NOT EXISTS idx_verification_tokens_user ON auth.verification_tokens(user_id);
