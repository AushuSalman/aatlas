-- ============================================================================
--  V1  Foundations
--
--  Extensions, conventions and the helper functions every later migration leans
--  on. No business tables: those arrive with the modules that own them, so a
--  table and the code that reads it are reviewed together.
--
--  Conventions established here and expected by every business table:
--    id          uuid primary key default uuid_generate_v7()
--    tenant_id   uuid not null, first column of every composite index
--    created_at  timestamptz not null default now()
--    updated_at  timestamptz not null, maintained by trigger
--    version     bigint not null default 0   (JPA optimistic locking)
--    money       numeric(14,4), stored in USD with the currency beside it
-- ============================================================================

-- pgcrypto: gen_random_bytes for token hashing and uuid fallbacks.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
-- pg_trgm: the jump-to search (products, branches, suppliers, customers).
CREATE EXTENSION IF NOT EXISTS pg_trgm;
-- btree_gin: mixed equality + range indexes on the partitioned fact tables.
CREATE EXTENSION IF NOT EXISTS btree_gin;

-- ---------------------------------------------------------------------------
--  app schema: helpers, kept out of public so they cannot collide with a table
-- ---------------------------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS app;

-- ---------------------------------------------------------------------------
--  uuid_generate_v7
--
--  Time-ordered UUIDs (RFC 9562). Random v4 keys scatter inserts across the
--  whole B-tree, which turns a 24-month import into random I/O and bloats the
--  index. v7 keys sort by creation time, so inserts stay at the right-hand edge
--  of the index and recent rows cluster together - which is exactly how the
--  ledger, history and audit log are read.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION app.uuid_generate_v7()
RETURNS uuid
LANGUAGE plpgsql
VOLATILE
AS $$
DECLARE
    unix_ts_ms bytea;
    uuid_bytes bytea;
BEGIN
    unix_ts_ms := substring(int8send((extract(epoch FROM clock_timestamp()) * 1000)::bigint) FROM 3);

    -- 10 random bytes after the 6-byte timestamp
    uuid_bytes := unix_ts_ms || gen_random_bytes(10);

    -- version 7 (0b0111) in the high nibble of byte 7
    uuid_bytes := set_byte(uuid_bytes, 6, 112 | (get_byte(uuid_bytes, 6) & 15));
    -- RFC 4122 variant in the top two bits of byte 9
    uuid_bytes := set_byte(uuid_bytes, 8, (get_byte(uuid_bytes, 8) & 63) | 128);

    RETURN encode(uuid_bytes, 'hex')::uuid;
END;
$$;

COMMENT ON FUNCTION app.uuid_generate_v7() IS
    'Time-ordered UUID v7. Default for every business primary key so inserts stay clustered.';

-- ---------------------------------------------------------------------------
--  current_tenant
--
--  Reads the tenant the application put on the connection from the JWT. Every
--  row-level security policy compares against this, so a query that forgets its
--  tenant predicate returns nothing rather than another company's prices.
--
--  Returns NULL rather than raising when unset, so migrations and maintenance
--  jobs running as the owner are not blocked.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION app.current_tenant()
RETURNS uuid
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    raw text;
BEGIN
    raw := current_setting('app.tenant_id', true);
    IF raw IS NULL OR raw = '' THEN
        RETURN NULL;
    END IF;
    RETURN raw::uuid;
EXCEPTION
    WHEN invalid_text_representation THEN
        RETURN NULL;
END;
$$;

COMMENT ON FUNCTION app.current_tenant() IS
    'The tenant bound to this connection by TenantAwareDataSource. Used by every RLS policy.';

-- ---------------------------------------------------------------------------
--  touch_updated_at
--
--  One trigger function, attached by every business table, so updated_at is
--  true even when a row is changed by a migration or by hand rather than
--  through JPA auditing.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION app.touch_updated_at()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------
--  enable_tenant_rls(table)
--
--  Applied to each tenant-scoped table as it is created. Keeping the policy in
--  one function means all sixty tables get the same rule, and changing the rule
--  is one edit rather than sixty.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION app.enable_tenant_rls(target regclass)
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', target);
    EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', target);
    EXECUTE format($p$
        CREATE POLICY tenant_isolation ON %s
        USING (tenant_id = app.current_tenant())
        WITH CHECK (tenant_id = app.current_tenant())
    $p$, target);
END;
$$;

COMMENT ON FUNCTION app.enable_tenant_rls(regclass) IS
    'Applies the standard tenant isolation policy. Call once per tenant-scoped table.';

-- ---------------------------------------------------------------------------
--  ShedLock
--
--  One row per scheduled job. With several pods behind a load balancer, every
--  one of them fires the same cron; this is what makes exactly one of them run
--  the nightly engine pass.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS shedlock (
    name       varchar(64)  NOT NULL,
    lock_until timestamptz  NOT NULL,
    locked_at  timestamptz  NOT NULL,
    locked_by  varchar(255) NOT NULL,
    PRIMARY KEY (name)
);

COMMENT ON TABLE shedlock IS 'Distributed locks for @Scheduled jobs. Not tenant-scoped.';
