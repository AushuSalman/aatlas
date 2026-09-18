-- ============================================================================
--  V28  Email verification codes
--
--  Signup proves the address before an account exists: a six-digit code is
--  mailed, typed back, and only then may a password be set against it.
--
--  One row per challenge. Only a SHA-256 of the code is stored, salted with
--  the address so two addresses with the same code do not share a digest. A
--  six-digit code is not full entropy, so the digest alone would not survive a
--  determined offline attack; what protects it is the ten-minute life and the
--  five attempts, both enforced here rather than trusted to the client.
--
--  Not under RLS and not tenant-scoped: the whole point is that no tenant or
--  user exists yet.
-- ============================================================================
CREATE TABLE email_verifications (
    id                  uuid        PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    email               text        NOT NULL,
    email_normalised    text        NOT NULL,
    code_hash           bytea       NOT NULL,
    expires_at          timestamptz NOT NULL,
    attempts_left       integer     NOT NULL,
    resend_count        integer     NOT NULL DEFAULT 0,
    resend_available_at timestamptz NOT NULL,
    verified_at         timestamptz,
    consumed_at         timestamptz,
    client_ip           text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    version             bigint      NOT NULL DEFAULT 0,

    CONSTRAINT email_verifications_hash_ck     CHECK (length(code_hash) = 32),
    CONSTRAINT email_verifications_email_ck    CHECK (position('@' IN email_normalised) > 1),
    CONSTRAINT email_verifications_attempts_ck CHECK (attempts_left >= 0)
);

-- "How many codes has this address been sent lately", the per-address cap.
CREATE INDEX email_verifications_email_idx ON email_verifications (email_normalised, created_at DESC);

CREATE TRIGGER email_verifications_touch_updated_at
    BEFORE UPDATE ON email_verifications
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

COMMENT ON TABLE email_verifications IS 'Six-digit signup codes. Only a salted SHA-256 is stored; ten minutes, five attempts.';
