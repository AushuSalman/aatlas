-- ============================================================================
--  V30  Users and access: access levels, per-user permissions, invitations
--
--  Two kinds of role, kept apart as the frontend keeps them. seat_role is the
--  job function (which workspaces, what limits). workspace_role is the access
--  level: who may invite, change and remove people. A Head of Sales is not
--  automatically an admin, and whoever created the workspace owns it.
-- ============================================================================

ALTER TABLE users
    ADD COLUMN workspace_role text        NOT NULL DEFAULT 'member',
    -- Null: the job function's defaults. Otherwise {modules, bulk, guardrails, approveLimit}
    -- in place of them, set by an admin on the Users screen.
    ADD COLUMN permissions    jsonb,
    ADD COLUMN invited_by     uuid        REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN invited_at     timestamptz;

ALTER TABLE users
    ADD CONSTRAINT users_workspace_role_ck CHECK (workspace_role IN ('super-admin', 'admin', 'member'));

-- INVITED: created by an admin, no password yet. DISABLED now also means "removed
-- from the workspace": the row stays so their decisions keep an author.
ALTER TABLE users DROP CONSTRAINT users_status_ck;
ALTER TABLE users
    ADD CONSTRAINT users_status_ck CHECK (status IN ('ACTIVE', 'INVITED', 'SUSPENDED', 'DISABLED'));

-- Every workspace so far was created by signup, and its first user created it.
UPDATE users u
   SET workspace_role = 'super-admin'
 WHERE u.id = (SELECT first.id FROM users first
                WHERE first.tenant_id = u.tenant_id
                ORDER BY first.created_at, first.id
                LIMIT 1);

COMMENT ON COLUMN users.workspace_role IS 'Access level: super-admin (created the workspace), admin, member.';
COMMENT ON COLUMN users.permissions IS 'Per-user access replacing the job function defaults; null keeps the defaults.';

-- ---------------------------------------------------------------------------
--  user_invitations
--
--  The emailed link that lets an invited person set a password. Same shape as
--  password_reset_tokens: only the SHA-256 is stored, single use, and not under
--  RLS because accepting happens before the person has a session.
-- ---------------------------------------------------------------------------
CREATE TABLE user_invitations (
    id          uuid        PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id   uuid        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_id     uuid        NOT NULL REFERENCES users (id)   ON DELETE CASCADE,
    token_hash  bytea       NOT NULL,
    invited_by  uuid        REFERENCES users (id) ON DELETE SET NULL,
    expires_at  timestamptz NOT NULL,
    accepted_at timestamptz,
    revoked_at  timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     bigint      NOT NULL DEFAULT 0,

    CONSTRAINT user_invitations_hash_ck CHECK (length(token_hash) = 32)
);

CREATE UNIQUE INDEX user_invitations_hash_uk ON user_invitations (token_hash);
CREATE INDEX user_invitations_user_idx ON user_invitations (tenant_id, user_id, created_at DESC);

CREATE TRIGGER user_invitations_touch_updated_at
    BEFORE UPDATE ON user_invitations
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

COMMENT ON TABLE user_invitations IS 'Emailed invitation links. Only the SHA-256 is stored; seven days; single use.';

-- ---------------------------------------------------------------------------
--  user_audit
--
--  Who invited, changed, suspended or removed whom. Names are copied in rather
--  than joined, so the log still reads correctly after someone is removed.
--  Not under RLS, like users: accepting an invitation writes here before the
--  person has a session. Every read filters on tenant_id.
-- ---------------------------------------------------------------------------
CREATE TABLE user_audit (
    id          uuid        PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id   uuid        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    actor_id    uuid,
    actor_name  text        NOT NULL,
    action      text        NOT NULL,
    target      text        NOT NULL,
    detail      text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     bigint      NOT NULL DEFAULT 0,

    CONSTRAINT user_audit_action_ck CHECK (action IN (
        'invited', 'updated', 'suspended', 'reactivated', 'removed', 'invite_resent', 'activated'))
);

CREATE INDEX user_audit_tenant_idx ON user_audit (tenant_id, created_at DESC);

COMMENT ON TABLE user_audit IS 'Invitations and access changes, with who made them.';
