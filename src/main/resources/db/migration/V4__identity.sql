-- ============================================================================
--  V4  Identity and tenant
--
--  The first business tables. Three of them, and they arrive together because
--  signup writes all three in one transaction: a company, its first user, and
--  the refresh token that user leaves with.
--
--  Conventions from V1 apply: uuid v7 primary keys, tenant_id first in every
--  composite index, created_at / updated_at / version on every row, and
--  updated_at maintained by trigger rather than trusted to the ORM.
-- ============================================================================

-- ---------------------------------------------------------------------------
--  tenants
--
--  One row per customer company. Created by signup; everything else in the
--  product hangs off it. Country is not decoration: it selects the map, the
--  subdivision vocabulary (states vs regions) and the trading currency, which
--  is why it is a checked column rather than free text.
-- ---------------------------------------------------------------------------
CREATE TABLE tenants (
    id               uuid        PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    name             text        NOT NULL,
    slug             text        NOT NULL,
    country          varchar(2)  NOT NULL,
    trading_currency varchar(3)  NOT NULL,
    status           text        NOT NULL DEFAULT 'ACTIVE',
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    version          bigint      NOT NULL DEFAULT 0,

    CONSTRAINT tenants_name_ck     CHECK (length(btrim(name)) BETWEEN 1 AND 200),
    CONSTRAINT tenants_country_ck  CHECK (country IN ('US', 'UK')),
    CONSTRAINT tenants_currency_ck CHECK (trading_currency IN ('USD', 'GBP')),
    CONSTRAINT tenants_status_ck   CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
);

-- The slug is what a future vanity URL and the support tooling look a tenant up
-- by, so it has to be unique even though nothing reads it yet.
CREATE UNIQUE INDEX tenants_slug_uk ON tenants (slug);

CREATE TRIGGER tenants_touch_updated_at
    BEFORE UPDATE ON tenants
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

COMMENT ON TABLE  tenants IS 'One customer company. Country selects the map, regions and trading currency.';
COMMENT ON COLUMN tenants.slug IS 'URL-safe form of the name, unique. Reserved for vanity URLs and support tooling.';

-- ---------------------------------------------------------------------------
--  users
--
--  email_normalised, not email, carries the unique index. Storing both means
--  the address is shown back exactly as it was typed, while "Alex@Corp.com"
--  and "alex@corp.com " can never become two accounts.
--
--  Uniqueness is global rather than per tenant on purpose: the sign-in screen
--  asks for an email and a password and nothing else, so an address has to
--  identify exactly one account for that form to be answerable.
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id                 uuid        PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id          uuid        NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    email              text        NOT NULL,
    email_normalised   text        NOT NULL,
    password_hash      text        NOT NULL,
    full_name          text        NOT NULL,
    title              text        NOT NULL,
    seat_role          text        NOT NULL,
    status             text        NOT NULL DEFAULT 'ACTIVE',
    email_verified     boolean     NOT NULL DEFAULT false,
    last_login_at      timestamptz,
    failed_login_count integer     NOT NULL DEFAULT 0,
    locked_until       timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    version            bigint      NOT NULL DEFAULT 0,

    CONSTRAINT users_email_ck     CHECK (position('@' IN email_normalised) > 1),
    CONSTRAINT users_full_name_ck CHECK (length(btrim(full_name)) BETWEEN 1 AND 120),
    CONSTRAINT users_status_ck    CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DISABLED')),
    CONSTRAINT users_seat_role_ck CHECK (seat_role IN (
        'sales-rep', 'seller', 'sales-head',
        'purchase-manager', 'buyer', 'purchase-head',
        'finance', 'both'))
);

-- The login lookup, and the constraint that makes signup's duplicate check race-proof.
CREATE UNIQUE INDEX users_email_normalised_uk ON users (email_normalised);
-- tenant_id first: the "who is on this account" listing, and the shape every
-- tenant-scoped index in this schema follows.
CREATE INDEX users_tenant_email_idx ON users (tenant_id, email_normalised);

CREATE TRIGGER users_touch_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

COMMENT ON TABLE  users IS 'A person with a seat. seat_role decides which workspaces open and what they may approve.';
COMMENT ON COLUMN users.email_normalised IS 'lower(btrim(email)). Carries the unique index so case and padding cannot fork an account.';
COMMENT ON COLUMN users.seat_role IS 'Matches the frontend Role union in src/lib/platform/types.ts exactly.';

-- ---------------------------------------------------------------------------
--  refresh_tokens
--
--  Opaque, single-use, rotated. Only the SHA-256 of the token is stored, so a
--  stolen database dump cannot be replayed against the API - the same reason
--  passwords are not stored either.
--
--  Rows are kept after use rather than deleted: replaced_by chains a family
--  together, which is what makes reuse detection possible. Presenting a token
--  that has already been used means it leaked, and the whole family is revoked.
-- ---------------------------------------------------------------------------
CREATE TABLE refresh_tokens (
    id             uuid        PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id      uuid        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_id        uuid        NOT NULL REFERENCES users (id)   ON DELETE CASCADE,
    token_hash     bytea       NOT NULL,
    issued_at      timestamptz NOT NULL,
    expires_at     timestamptz NOT NULL,
    used_at        timestamptz,
    revoked_at     timestamptz,
    revoked_reason text,
    replaced_by    uuid        REFERENCES refresh_tokens (id) ON DELETE SET NULL,
    user_agent     text,
    -- text rather than inet: nothing here does subnet arithmetic, and inet needs a
    -- custom Hibernate type that ddl-auto=validate then has to be taught about.
    client_ip      text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    version        bigint      NOT NULL DEFAULT 0,

    CONSTRAINT refresh_tokens_hash_ck   CHECK (length(token_hash) = 32),
    CONSTRAINT refresh_tokens_window_ck CHECK (expires_at > issued_at)
);

-- The presented-token lookup. Unique because a hash collision would be a bug.
CREATE UNIQUE INDEX refresh_tokens_hash_uk ON refresh_tokens (token_hash);
-- "Sign out everywhere" and the nightly purge of dead rows.
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (tenant_id, user_id, expires_at DESC);

CREATE TRIGGER refresh_tokens_touch_updated_at
    BEFORE UPDATE ON refresh_tokens
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

COMMENT ON TABLE  refresh_tokens IS 'Opaque rotating refresh tokens. Only the SHA-256 is stored; reuse revokes the family.';
COMMENT ON COLUMN refresh_tokens.replaced_by IS 'The token issued in exchange for this one. Chains a family for reuse detection.';

-- ---------------------------------------------------------------------------
--  Row-level security is deliberately NOT enabled on these three tables yet.
--
--  aatlas.rls.enabled is still false, and two operations here run before a
--  tenant exists to bind against: signup inserts the tenant and its first user
--  in one transaction, and login has to find a user by email before it knows
--  which tenant that user belongs to. Both are pre-tenant by nature.
--
--  When RLS is switched on, these are the exceptions - users and
--  refresh_tokens need a policy that also permits the authentication path,
--  not a bare app.enable_tenant_rls() call.
-- ---------------------------------------------------------------------------
