-- ============================================================================
--  Magic links: sign in from an emailed link, no password typed.
--
--  The same shape as password_reset_tokens (V5): only the SHA-256 of the token is
--  stored, one row per link, single use, short lived. Not under row-level security -
--  the link is consumed before there is a signed-in tenant, exactly like a reset.
-- ============================================================================

CREATE TABLE magic_link_tokens (
    id          uuid        PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id   uuid        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_id     uuid        NOT NULL REFERENCES users (id)   ON DELETE CASCADE,
    token_hash  bytea       NOT NULL,
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    client_ip   text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     bigint      NOT NULL DEFAULT 0,

    CONSTRAINT magic_link_tokens_hash_ck CHECK (length(token_hash) = 32)
);

CREATE UNIQUE INDEX magic_link_tokens_hash_uk ON magic_link_tokens (token_hash);
CREATE INDEX magic_link_tokens_user_idx ON magic_link_tokens (tenant_id, user_id, expires_at DESC);

CREATE TRIGGER magic_link_tokens_touch_updated_at
    BEFORE UPDATE ON magic_link_tokens
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

COMMENT ON TABLE magic_link_tokens IS 'Single-use sign-in links. Only the SHA-256 is stored; fifteen minutes to live.';
