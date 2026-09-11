-- ============================================================================
--  V16  Row-level security on partitions
--
--  Fixes a hole opened by V15.
--
--  Enabling row-level security on a partitioned table protects it only when it
--  is read through the parent. PostgreSQL does not cascade policies to
--  partitions: each one carries its own, and a partition created without any is
--  wide open to a query that names it directly.
--
--      set app.tenant_id = '';                       -- no tenant bound
--      select count(*) from sales_transactions;      -- 0, as intended
--      select count(*) from sales_transactions_2026_08;  -- every tenant's rows
--
--  app.ensure_month_partition() created bare partitions, so every month a load
--  touched was reachable that way. Nothing in the application addresses a
--  partition by name, so this was not exploitable through the API - but "no
--  current caller does the wrong thing" is not an access control, and the whole
--  point of RLS here is to be the backstop when a query forgets its predicate.
-- ============================================================================

-- ---------------------------------------------------------------------------
--  ensure_month_partition, now securing what it creates
--
--  The policy is applied inside the same statement that creates the partition,
--  so there is no window in which the table exists without one.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION app.ensure_month_partition(parent regclass, on_date date)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    start_of_month date := date_trunc('month', on_date)::date;
    next_month     date := (date_trunc('month', on_date) + interval '1 month')::date;
    partition_name text := format('%s_%s', parent::text, to_char(start_of_month, 'YYYY_MM'));
BEGIN
    IF to_regclass(partition_name) IS NOT NULL THEN
        RETURN;
    END IF;

    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS %I PARTITION OF %s FOR VALUES FROM (%L) TO (%L)',
        partition_name, parent::text, start_of_month, next_month);

    -- The same isolation the parent carries. Without this the partition is
    -- readable by name regardless of the tenant on the connection.
    PERFORM app.enable_tenant_rls(partition_name::regclass);
EXCEPTION
    -- Two importers racing on the same month. The loser does not care: the
    -- partition it needed now exists, and the winner secured it.
    WHEN duplicate_table THEN
        RETURN;
END;
$$;

COMMENT ON FUNCTION app.ensure_month_partition(regclass, date) IS
    'Creates the monthly partition covering on_date and applies tenant isolation to it. Idempotent and race-safe.';

-- ---------------------------------------------------------------------------
--  Secure the partitions V15 already created
--
--  Existing partitions were created without a policy, so they are fixed in
--  place rather than left for the next load to notice. Guarded on the policy
--  not already existing, so this is safe to run against a database where some
--  partitions were created after the function above was replaced.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    partition_name text;
BEGIN
    FOR partition_name IN
        SELECT c.relname
          FROM pg_inherits i
          JOIN pg_class c ON c.oid = i.inhrelid
          JOIN pg_class p ON p.oid = i.inhparent
         WHERE p.relname = 'sales_transactions'
           AND NOT EXISTS (
               SELECT 1 FROM pg_policy pol
                WHERE pol.polrelid = c.oid AND pol.polname = 'tenant_isolation')
    LOOP
        PERFORM app.enable_tenant_rls(partition_name::regclass);
        RAISE NOTICE 'Applied tenant isolation to partition %', partition_name;
    END LOOP;
END;
$$;
