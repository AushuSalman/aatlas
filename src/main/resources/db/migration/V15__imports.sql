-- ============================================================================
--  V15  CSV import and the sales fact table
--
--  Three tables and one helper. import_batches is the report the connect screen
--  renders and also the job record the commit polls; import_issues is the row
--  detail behind it; sales_transactions is where accepted rows land and is the
--  table every sell-side number is eventually derived from.
--
--  The validation rules these tables record are a port of the frontend's
--  src/lib/platform/ingest.ts. That file is the specification - it is what the
--  connect screen already applies in the browser - so the Java side reproduces
--  it rather than inventing a second dialect of "valid".
-- ============================================================================

-- ---------------------------------------------------------------------------
--  ensure_month_partition
--
--  Creates the monthly partition covering on_date if it is not there yet.
--  Called by the loader before inserting, because an import arrives with a date
--  range nobody declared in advance - a 24-month history lands in 24 partitions
--  that have to exist first.
--
--  There is deliberately no DEFAULT partition. A default would accept rows for
--  a month with no partition, and PostgreSQL then refuses to create that
--  partition later while the default holds matching rows - turning a silent
--  convenience into a migration that cannot be run.
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
EXCEPTION
    -- Two importers racing on the same month. The loser does not care: the
    -- partition it needed now exists.
    WHEN duplicate_table THEN
        RETURN;
END;
$$;

COMMENT ON FUNCTION app.ensure_month_partition(regclass, date) IS
    'Creates the monthly partition covering on_date if absent. Idempotent and race-safe.';

-- ---------------------------------------------------------------------------
--  import_batches
--
--  One row per uploaded file. It carries three things at once, and that is
--  deliberate rather than lazy: the stored file's location, the report the
--  screen renders, and the status the commit is polled through. Splitting them
--  would mean three writes and three reads for one conceptual thing that is
--  always fetched together.
--
--  The report columns are the shape of ImportReport in ingest.ts. Counts and
--  the date range are real columns because they are filtered and sorted on;
--  headers, mapping, sample and missing_required are jsonb because the screen
--  renders them verbatim and nothing queries inside them.
-- ---------------------------------------------------------------------------
CREATE TABLE import_batches (
    id               uuid         PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id        uuid         NOT NULL REFERENCES tenants (id)      ON DELETE CASCADE,
    data_source_id   uuid                  REFERENCES data_sources (id) ON DELETE SET NULL,

    -- The stored upload. file_key is opaque to this table; ImportFileStore owns its shape.
    file_key         text         NOT NULL,
    file_name        text         NOT NULL,
    file_size        bigint       NOT NULL,

    status           text         NOT NULL DEFAULT 'VALIDATED',

    headers          jsonb        NOT NULL DEFAULT '[]'::jsonb,
    mapping          jsonb        NOT NULL DEFAULT '{}'::jsonb,
    missing_required jsonb        NOT NULL DEFAULT '[]'::jsonb,
    sample           jsonb        NOT NULL DEFAULT '[]'::jsonb,

    total_rows         integer    NOT NULL DEFAULT 0,
    accepted_rows      integer    NOT NULL DEFAULT 0,
    rejected_rows      integer    NOT NULL DEFAULT 0,
    distinct_items     integer    NOT NULL DEFAULT 0,
    distinct_customers integer    NOT NULL DEFAULT 0,
    distinct_branches  integer    NOT NULL DEFAULT 0,
    months_covered     integer    NOT NULL DEFAULT 0,
    earliest           date,
    latest             date,

    -- Filled by the commit: what actually landed, which is not the same as what
    -- validated - a branch or customer the catalogue has never seen is accepted
    -- into the fact table with its code kept and its foreign key left null.
    loaded_rows        integer,
    products_created   integer,
    unresolved_branches  jsonb   NOT NULL DEFAULT '[]'::jsonb,
    unresolved_customers jsonb   NOT NULL DEFAULT '[]'::jsonb,

    failure_reason   text,
    uploaded_by      uuid         REFERENCES users (id) ON DELETE SET NULL,
    committed_at     timestamptz,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    version          bigint       NOT NULL DEFAULT 0,

    CONSTRAINT import_batches_status_ck CHECK (status IN (
        'VALIDATED',      -- parsed and checked; ready to commit
        'NEEDS_MAPPING',  -- a required field has no column; the user must choose one
        'COMMITTING',     -- the loader is running
        'COMMITTED',      -- rows are in sales_transactions
        'FAILED')),
    CONSTRAINT import_batches_counts_ck CHECK (
        total_rows >= 0 AND accepted_rows >= 0 AND rejected_rows >= 0
        AND accepted_rows + rejected_rows <= total_rows),
    CONSTRAINT import_batches_range_ck CHECK (earliest IS NULL OR latest IS NULL OR earliest <= latest),
    -- A committed batch has to say what it loaded; anything else is a report nobody can trust.
    CONSTRAINT import_batches_committed_ck CHECK (
        status <> 'COMMITTED' OR (committed_at IS NOT NULL AND loaded_rows IS NOT NULL))
);

CREATE INDEX import_batches_tenant_idx ON import_batches (tenant_id, created_at DESC);

CREATE TRIGGER import_batches_touch_updated_at
    BEFORE UPDATE ON import_batches
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('import_batches');

COMMENT ON TABLE  import_batches IS 'One uploaded file: where it is stored, the validation report, and the commit job status.';
COMMENT ON COLUMN import_batches.mapping IS 'field key to zero-based column index, as chosen by detection or by the user.';
COMMENT ON COLUMN import_batches.unresolved_branches IS 'Branch codes in the file that match no store. Their rows load with store_id null.';

-- ---------------------------------------------------------------------------
--  import_issues
--
--  One row per problem found, at most a bounded number per batch - a file with
--  every row broken produces a report, not a million rows of it. Errors reject
--  the row; warnings keep it and say so, which is the distinction the connect
--  screen draws in its issues table.
-- ---------------------------------------------------------------------------
CREATE TABLE import_issues (
    id         uuid         PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id  uuid         NOT NULL REFERENCES tenants (id)        ON DELETE CASCADE,
    batch_id   uuid         NOT NULL REFERENCES import_batches (id) ON DELETE CASCADE,
    line       integer      NOT NULL,
    field      text         NOT NULL,
    message    text         NOT NULL,
    severity   text         NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now(),
    -- An issue is never edited - revalidation deletes and rewrites the set - so these two
    -- carry no meaning here. They are present because every JPA entity in this codebase
    -- extends BaseEntity, and BaseEntity owns updated_at and the optimistic lock. Paying a
    -- column for a consistent base class is the cheaper side of that trade.
    updated_at timestamptz  NOT NULL DEFAULT now(),
    version    bigint       NOT NULL DEFAULT 0,

    CONSTRAINT import_issues_severity_ck CHECK (severity IN ('error', 'warning')),
    -- 1-based and counting the header, so the number matches what a spreadsheet shows.
    CONSTRAINT import_issues_line_ck     CHECK (line >= 1)
);

-- The issues table is read by batch, filtered by severity, ordered by line.
CREATE INDEX import_issues_batch_idx ON import_issues (tenant_id, batch_id, severity, line);

CREATE TRIGGER import_issues_touch_updated_at
    BEFORE UPDATE ON import_issues
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('import_issues');

COMMENT ON TABLE  import_issues IS 'Row-level problems found during validation. Errors reject the row; warnings do not.';
COMMENT ON COLUMN import_issues.line IS '1-based line in the file, counting the header - the number a spreadsheet shows.';

-- ---------------------------------------------------------------------------
--  sales_transactions
--
--  The big one, and the only append-only table in the schema. No updated_at and
--  no version column: a transaction is a fact that happened, never edited. A
--  correction is a new row or a re-import, never an UPDATE.
--
--  Partitioned by month on txn_date. A 24-month import lands in 24 fresh
--  partitions, old ones detach cheaply when retention arrives, and every index
--  stays small enough to matter. The primary key has to contain the partition
--  key, which is no loss: leading it with tenant_id gives the tenant-scoped
--  index every query here wants anyway.
--
--  Both the resolved id and the raw code are stored for item, branch and
--  customer. The codes are what the file said and never change; the ids are
--  this platform's view of them and can be filled in later. A branch the
--  catalogue has never heard of must not reject a row - the history is still
--  true, and refusing it would make importing a real ERP export impossible
--  until every branch had been set up by hand first.
-- ---------------------------------------------------------------------------
CREATE TABLE sales_transactions (
    id              uuid          NOT NULL DEFAULT app.uuid_generate_v7(),
    tenant_id       uuid          NOT NULL,
    txn_date        date          NOT NULL,

    product_id      uuid          REFERENCES products  (id) ON DELETE RESTRICT,
    store_id        uuid          REFERENCES stores    (id) ON DELETE SET NULL,
    customer_id     uuid          REFERENCES customers (id) ON DELETE SET NULL,

    item_number     text          NOT NULL,
    branch_code     text,
    customer_code   text,
    description     text,

    qty             numeric(14,4) NOT NULL,
    unit_price      numeric(14,4) NOT NULL,
    unit_cost       numeric(14,4),

    source          text          NOT NULL DEFAULT 'import',
    import_batch_id uuid,
    source_line     integer,
    created_at      timestamptz   NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, txn_date, id),

    CONSTRAINT sales_transactions_qty_ck    CHECK (qty > 0),
    CONSTRAINT sales_transactions_price_ck  CHECK (unit_price >= 0),
    CONSTRAINT sales_transactions_cost_ck   CHECK (unit_cost IS NULL OR unit_cost >= 0),
    CONSTRAINT sales_transactions_source_ck CHECK (source IN ('import', 'erp', 'warehouse', 'sample', 'manual'))
) PARTITION BY RANGE (txn_date);

-- The pricing engine's read: this item, at this branch, over a window.
CREATE INDEX sales_transactions_line_idx
    ON sales_transactions (tenant_id, product_id, store_id, txn_date);
-- Re-import and rollback: everything one batch loaded.
CREATE INDEX sales_transactions_batch_idx
    ON sales_transactions (tenant_id, import_batch_id);

SELECT app.enable_tenant_rls('sales_transactions');

COMMENT ON TABLE  sales_transactions IS 'Append-only sales history, partitioned by month. Every sell-side number derives from this.';
COMMENT ON COLUMN sales_transactions.branch_code IS 'The branch as the file spelled it. Kept even when store_id resolves, and the only record when it does not.';
COMMENT ON COLUMN sales_transactions.qty IS 'Always positive: returns and credits are rejected at validation, not stored as negatives.';
