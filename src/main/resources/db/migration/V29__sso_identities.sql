-- ============================================================================
--  V29  Sign in with Google and Apple
--
--  An account can now exist without a password: someone who signs up with a
--  provider never sets one. password_hash becomes nullable, and login refuses
--  a password for such an account rather than comparing against nothing.
-- ============================================================================
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

-- ---------------------------------------------------------------------------
--  user_identities
--
--  A provider account linked to a user. Linked on (provider, subject) - the
--  provider's stable id - never on email, which can change hands at the
--  provider while the subject cannot.
--
--  Not under RLS: sign-in looks the identity up before any tenant is known,
--  exactly like users and refresh_tokens.
-- ---------------------------------------------------------------------------
CREATE TABLE user_identities (
    id          uuid        PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id   uuid        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_id     uuid        NOT NULL REFERENCES users (id)   ON DELETE CASCADE,
    provider    text        NOT NULL,
    subject     text        NOT NULL,
    email       text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     bigint      NOT NULL DEFAULT 0,

    CONSTRAINT user_identities_provider_ck CHECK (provider IN ('google', 'apple'))
);

CREATE UNIQUE INDEX user_identities_subject_uk ON user_identities (provider, subject);
-- One identity per provider per user.
CREATE UNIQUE INDEX user_identities_user_provider_uk ON user_identities (user_id, provider);

CREATE TRIGGER user_identities_touch_updated_at
    BEFORE UPDATE ON user_identities
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

COMMENT ON TABLE user_identities IS 'Google / Apple accounts linked to a user, by the provider''s stable subject.';

-- ---------------------------------------------------------------------------
--  sso_tickets
--
--  The API's word that it verified a provider identity. Handed to the browser
--  after the code exchange, then presented to sign in or to sign up - so the
--  browser carries a claim the API made, never an identity it asserts itself.
--  Only the SHA-256 is stored; thirty minutes; single use.
-- ---------------------------------------------------------------------------
CREATE TABLE sso_tickets (
    id               uuid        PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    token_hash       bytea       NOT NULL,
    provider         text        NOT NULL,
    subject          text        NOT NULL,
    email            text        NOT NULL,
    email_verified   boolean     NOT NULL,
    full_name        text,
    private_relay    boolean     NOT NULL DEFAULT false,
    -- The provider owns the mailbox (Gmail, a Workspace domain, Apple): only then
    -- may the identity be linked to an existing account by its address.
    email_authoritative boolean  NOT NULL DEFAULT false,
    expires_at       timestamptz NOT NULL,
    consumed_at      timestamptz,
    client_ip        text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    version          bigint      NOT NULL DEFAULT 0,

    CONSTRAINT sso_tickets_hash_ck     CHECK (length(token_hash) = 32),
    CONSTRAINT sso_tickets_provider_ck CHECK (provider IN ('google', 'apple'))
);

CREATE UNIQUE INDEX sso_tickets_hash_uk ON sso_tickets (token_hash);

CREATE TRIGGER sso_tickets_touch_updated_at
    BEFORE UPDATE ON sso_tickets
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

COMMENT ON TABLE sso_tickets IS 'A provider identity the API verified, redeemable once within 30 minutes.';
