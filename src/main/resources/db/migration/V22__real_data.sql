-- ============================================================================
--  V22  Real data
--
--  Until now the only fact table anything wrote was sales_transactions, and
--  nothing read it: every cost, price, unit and saving on every screen was a
--  hash of an item or store code. This migration is the schema for the
--  read layer that replaces that, and for the three imports that feed it.
--
--    import_batches       four kinds of file, a source (upload | sample), a
--                         rollback state and a per-kind commit summary
--    purchase_order       becomes an importable fact table: one row per PO
--                         LINE (the unique po_number index goes), nullable
--                         delivery fields, product/store ids, an import trail
--    product_prices       what the customer has decided an item sells for and
--                         costs - the price list, with history and a basis
--    inventory_positions  stock on hand per item and branch
--    competitor_prices    prices observed at competitors, tagged by region
--    pricing_benchmarks   reference gross-margin bands per category
--
--  It also retires the hashed rows: the seeded procurement ledger and the
--  seeded deal history were fixtures presented as facts, and history now
--  starts empty for everyone. Sample tenants get real rows back through the
--  sample loader on the next boot.
--
--  Conventions from V1 apply. Every UPDATE/DELETE on a tenant table runs with
--  RLS lifted (the V20 pattern): app.current_tenant() is NULL on a migration
--  connection and FORCE applies to the owner, so an unbracketed backfill
--  matches nothing and reports success.
-- ============================================================================

-- ---------------------------------------------------------------------------
--  import_batches: kind, source, rollback, summary
-- ---------------------------------------------------------------------------
ALTER TABLE import_batches
    ADD COLUMN kind               text        NOT NULL DEFAULT 'sales',
    ADD COLUMN source             text        NOT NULL DEFAULT 'upload',
    ADD COLUMN content_hash       text,
    ADD COLUMN rolled_back_at     timestamptz,
    ADD COLUMN date_shift_days    integer     NOT NULL DEFAULT 0,
    ADD COLUMN distinct_suppliers integer     NOT NULL DEFAULT 0,
    ADD COLUMN commit_summary     jsonb       NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE import_batches
    ADD CONSTRAINT import_batches_kind_ck
        CHECK (kind IN ('sales', 'purchases', 'products', 'competitor_prices')),
    ADD CONSTRAINT import_batches_source_ck
        CHECK (source IN ('upload', 'sample'));

ALTER TABLE import_batches DROP CONSTRAINT import_batches_status_ck;
ALTER TABLE import_batches ADD CONSTRAINT import_batches_status_ck CHECK (status IN (
    'VALIDATED', 'NEEDS_MAPPING', 'COMMITTING', 'COMMITTED', 'FAILED', 'ROLLED_BACK'));
ALTER TABLE import_batches ADD CONSTRAINT import_batches_rolled_back_ck
    CHECK (status <> 'ROLLED_BACK' OR rolled_back_at IS NOT NULL);

CREATE INDEX import_batches_tenant_kind_idx ON import_batches (tenant_id, kind, created_at DESC);

-- The sample loader's claim: one live sample batch per kind per tenant. A
-- republished SampleDataConnected, a second pod, or a boot-time backfill that
-- races the listener all hit unique_violation on the second claim and skip.
CREATE UNIQUE INDEX import_batches_sample_kind_uk ON import_batches (tenant_id, kind)
    WHERE source = 'sample' AND status <> 'ROLLED_BACK';

COMMENT ON COLUMN import_batches.kind IS 'Which file this is: sales | purchases | products | competitor_prices. Decides the field set, validator and loader.';
COMMENT ON COLUMN import_batches.source IS 'upload = the tenant''s own file; sample = a classpath sample loaded on their behalf.';
COMMENT ON COLUMN import_batches.date_shift_days IS 'Sample files only: how many days every date was moved forward at load so the history ends today.';
COMMENT ON COLUMN import_batches.commit_summary IS 'Kind-specific counters from the commit (pricesWritten, suppliersCreated, ...). Rendered verbatim.';

-- ---------------------------------------------------------------------------
--  sales_transactions: the three optional sales columns
-- ---------------------------------------------------------------------------
ALTER TABLE sales_transactions
    ADD COLUMN invoice_no text,
    ADD COLUMN currency   text,
    ADD COLUMN uom        text;

-- ---------------------------------------------------------------------------
--  data_sources: one csv row per tenant (the server-side record of imports)
-- ---------------------------------------------------------------------------
CREATE UNIQUE INDEX data_sources_csv_uk ON data_sources (tenant_id) WHERE kind = 'csv';

-- ---------------------------------------------------------------------------
--  catalogue rows remember which import created them, so a rollback can find them
-- ---------------------------------------------------------------------------
ALTER TABLE products
    ADD COLUMN source          text NOT NULL DEFAULT 'sample',
    ADD COLUMN import_batch_id uuid,
    ADD CONSTRAINT products_source_ck CHECK (source IN ('sample', 'import', 'manual'));

ALTER TABLE stores ADD COLUMN import_batch_id uuid;

ALTER TABLE product_stores ADD COLUMN import_batch_id uuid;

ALTER TABLE customers
    ADD COLUMN source          text NOT NULL DEFAULT 'sample',
    ADD COLUMN import_batch_id uuid,
    ADD CONSTRAINT customers_source_ck CHECK (source IN ('sample', 'import', 'manual'));

-- A customer the sales file named and nobody has classified yet. A real state,
-- like an unassigned branch: shown as its own bucket, never guessed.
ALTER TABLE customers DROP CONSTRAINT customers_segment_ck;
ALTER TABLE customers ADD CONSTRAINT customers_segment_ck
    CHECK (segment IN ('contractor', 'institutional', 'industrial', 'walk-in', 'unassigned'));

-- ---------------------------------------------------------------------------
--  suppliers: a supplier known only from a purchase order has NULL figures
--
--  The purchases import creates a supplier from a name and a country. It must
--  not invent an on-time rate or a lead time for them - a placeholder would be
--  scored by the risk and rating formulas as if it were a fact. So the figures
--  become nullable and "not provided" is the honest state.
-- ---------------------------------------------------------------------------
ALTER TABLE suppliers
    ALTER COLUMN lead_time_days DROP NOT NULL,
    ALTER COLUMN otif_pct       DROP NOT NULL,
    ALTER COLUMN price_index    DROP NOT NULL,
    ALTER COLUMN defect_pct     DROP NOT NULL,
    ALTER COLUMN holds_stock    DROP NOT NULL,
    ALTER COLUMN years_trading  DROP NOT NULL,
    ALTER COLUMN since          DROP NOT NULL,
    ALTER COLUMN category       DROP NOT NULL,
    ALTER COLUMN vendor_code    SET DEFAULT '',
    ALTER COLUMN contact_name   SET DEFAULT '',
    ALTER COLUMN email          SET DEFAULT '',
    ADD COLUMN source           text,
    ADD COLUMN import_batch_id  uuid;

ALTER TABLE suppliers DROP CONSTRAINT suppliers_category_ck;
ALTER TABLE suppliers ADD CONSTRAINT suppliers_category_ck CHECK (category IS NULL OR category IN (
    'Copper & brass', 'Valves', 'Polymers', 'Steel', 'Tooling', 'Fittings'));
ALTER TABLE suppliers DROP CONSTRAINT suppliers_lead_ck;
ALTER TABLE suppliers ADD CONSTRAINT suppliers_lead_ck CHECK (lead_time_days IS NULL OR lead_time_days >= 0);
ALTER TABLE suppliers DROP CONSTRAINT suppliers_otif_ck;
ALTER TABLE suppliers ADD CONSTRAINT suppliers_otif_ck CHECK (otif_pct IS NULL OR otif_pct BETWEEN 0 AND 100);
ALTER TABLE suppliers DROP CONSTRAINT suppliers_defect_ck;
ALTER TABLE suppliers ADD CONSTRAINT suppliers_defect_ck CHECK (defect_pct IS NULL OR defect_pct >= 0);
ALTER TABLE suppliers ADD CONSTRAINT suppliers_source_ck CHECK (source IS NULL OR source IN (
    'sample', 'lookup', 'manual', 'import', 'po_import'));

ALTER TABLE supplier_ratings
    ALTER COLUMN quality       DROP NOT NULL,
    ALTER COLUMN delivery      DROP NOT NULL,
    ALTER COLUMN communication DROP NOT NULL,
    ALTER COLUMN pricing       DROP NOT NULL;

ALTER TABLE supplier_risk
    ALTER COLUMN score              DROP NOT NULL,
    ALTER COLUMN level              DROP NOT NULL,
    ALTER COLUMN capacity           DROP NOT NULL,
    ALTER COLUMN lead_variance_days DROP NOT NULL,
    ALTER COLUMN consistency        DROP NOT NULL,
    ALTER COLUMN trend              DROP NOT NULL,
    ALTER COLUMN recent_delay_pct   DROP NOT NULL;
ALTER TABLE supplier_risk DROP CONSTRAINT supplier_risk_score_ck;
ALTER TABLE supplier_risk ADD CONSTRAINT supplier_risk_score_ck CHECK (score IS NULL OR score BETWEEN 0 AND 100);
ALTER TABLE supplier_risk DROP CONSTRAINT supplier_risk_level_ck;
ALTER TABLE supplier_risk ADD CONSTRAINT supplier_risk_level_ck CHECK (level IS NULL OR level IN ('Low', 'Medium', 'High'));
ALTER TABLE supplier_risk DROP CONSTRAINT supplier_risk_capacity_ck;
ALTER TABLE supplier_risk ADD CONSTRAINT supplier_risk_capacity_ck CHECK (capacity IS NULL OR capacity IN ('High', 'Medium', 'Low'));
ALTER TABLE supplier_risk DROP CONSTRAINT supplier_risk_consistency_ck;
ALTER TABLE supplier_risk ADD CONSTRAINT supplier_risk_consistency_ck CHECK (consistency IS NULL OR consistency IN ('High', 'Medium', 'Low'));
ALTER TABLE supplier_risk DROP CONSTRAINT supplier_risk_trend_ck;
ALTER TABLE supplier_risk ADD CONSTRAINT supplier_risk_trend_ck CHECK (trend IS NULL OR trend IN ('Stable', 'Improving', 'Worsening'));

-- Which suppliers carry which item: where the ex-works figure came from.
ALTER TABLE supplier_products
    ADD COLUMN ex_works_source text,
    ADD COLUMN ex_works_as_of  date,
    ADD COLUMN import_batch_id uuid,
    ADD CONSTRAINT supplier_products_source_ck
        CHECK (ex_works_source IS NULL OR ex_works_source IN ('import', 'purchases', 'manual'));

-- ---------------------------------------------------------------------------
--  purchase_order: an importable fact table
--
--  One row per PO line. The V12 unique index on (tenant_id, po_number) assumed
--  one line per order, which no real export honours; imported rows get a
--  generated po_number and keep the file's reference in po_ref. Delivery
--  fields become nullable: null means "not measurable", never "late".
-- ---------------------------------------------------------------------------
ALTER TABLE purchase_order
    ADD COLUMN source             text    NOT NULL DEFAULT 'award',
    ADD COLUMN import_batch_id    uuid,
    ADD COLUMN source_line        integer,
    ADD COLUMN po_ref             text,
    ADD COLUMN product_id         uuid    REFERENCES products (id) ON DELETE RESTRICT,
    ADD COLUMN store_id           uuid    REFERENCES stores (id)   ON DELETE SET NULL,
    ADD COLUMN unit_cost_currency text,
    ADD COLUMN cost_basis         text    NOT NULL DEFAULT 'file',
    ADD COLUMN qty_received       integer,
    ADD CONSTRAINT purchase_order_source_ck       CHECK (source IN ('award', 'import', 'sample')),
    ADD CONSTRAINT purchase_order_cost_basis_ck   CHECK (cost_basis IN ('file', 'components', 'lane-estimate')),
    ADD CONSTRAINT purchase_order_qty_received_ck CHECK (qty_received IS NULL OR qty_received >= 0);

ALTER TABLE purchase_order
    ALTER COLUMN branch_id     DROP NOT NULL,
    ALTER COLUMN branch_name   DROP NOT NULL,
    ALTER COLUMN region_key    DROP NOT NULL,
    ALTER COLUMN region_label  DROP NOT NULL,
    ALTER COLUMN promised_date DROP NOT NULL,
    ALTER COLUMN promised_days DROP NOT NULL,
    ALTER COLUMN actual_days   DROP NOT NULL,
    ALTER COLUMN days_late     DROP NOT NULL,
    ALTER COLUMN on_time       DROP NOT NULL;

DROP INDEX purchase_order_tenant_po_uk;
CREATE INDEX        purchase_order_tenant_po_idx      ON purchase_order (tenant_id, po_number);
CREATE UNIQUE INDEX purchase_order_tenant_award_po_uk ON purchase_order (tenant_id, po_number) WHERE source = 'award';
CREATE UNIQUE INDEX purchase_order_tenant_line_uk     ON purchase_order (tenant_id, import_batch_id, source_line)
    WHERE import_batch_id IS NOT NULL;
CREATE INDEX purchase_order_tenant_item_date_idx     ON purchase_order (tenant_id, item_number, order_date);
CREATE INDEX purchase_order_tenant_product_date_idx  ON purchase_order (tenant_id, product_id, order_date);
CREATE INDEX purchase_order_tenant_supplier_date_idx ON purchase_order (tenant_id, supplier_id, order_date);
CREATE INDEX purchase_order_tenant_batch_idx         ON purchase_order (tenant_id, import_batch_id);

COMMENT ON COLUMN purchase_order.po_ref IS 'The PO number as the file wrote it. Many lines share one; po_number stays unique per row.';
COMMENT ON COLUMN purchase_order.on_time IS 'Received on or before promised (and in full when a received quantity is given). NULL = not measurable yet.';
COMMENT ON COLUMN purchase_order.cost_basis IS 'file = landed cost as given; components = ex-works + freight + duty from the file; lane-estimate = freight and duty from the reference lane.';

-- ---------------------------------------------------------------------------
--  deal: cost is nullable - a sale recorded without a cost on file is still a sale
-- ---------------------------------------------------------------------------
ALTER TABLE deal ALTER COLUMN cost DROP NOT NULL;
ALTER TABLE deal ALTER COLUMN cost DROP DEFAULT;

-- ---------------------------------------------------------------------------
--  commodities: reference data carries the date it was true on
-- ---------------------------------------------------------------------------
ALTER TABLE commodities ADD COLUMN as_of date;

-- ---------------------------------------------------------------------------
--  Backfills and the retirement of hashed rows, RLS lifted (V20 pattern)
-- ---------------------------------------------------------------------------
ALTER TABLE products          DISABLE ROW LEVEL SECURITY;
ALTER TABLE customers         DISABLE ROW LEVEL SECURITY;
ALTER TABLE suppliers         DISABLE ROW LEVEL SECURITY;
ALTER TABLE purchase_order    DISABLE ROW LEVEL SECURITY;
ALTER TABLE deal              DISABLE ROW LEVEL SECURITY;
ALTER TABLE sales_transactions DISABLE ROW LEVEL SECURITY;

-- Only SalesTransactionLoader ever wrote an uncategorised product.
UPDATE products p SET source = 'import'
 WHERE p.category = 'uncategorised'
   AND EXISTS (SELECT 1 FROM sales_transactions t WHERE t.tenant_id = p.tenant_id AND t.product_id = p.id);

UPDATE suppliers SET source = CASE
    WHEN supplier_key LIKE 'sup-%' THEN 'sample'
    WHEN supplier_key LIKE 'cus-%' THEN 'lookup'
    WHEN supplier_key LIKE 'csv-%' THEN 'import'
    WHEN supplier_key LIKE 'own-%' THEN 'manual'
    END
 WHERE source IS NULL;

-- LedgerBuilder's ledger ('PO-yymm-1xxx', hashed landed costs) and seed/deals.json
-- were fixtures presented as facts. Awards ('PO-AWD-...') and recorded deals stay.
DELETE FROM purchase_order WHERE po_number NOT LIKE 'PO-AWD-%';
DELETE FROM deal WHERE recorded = false;

UPDATE purchase_order po SET product_id = p.id
  FROM products p
 WHERE p.tenant_id = po.tenant_id AND p.item_number = po.item_number AND po.product_id IS NULL;

UPDATE purchase_order po SET store_id = s.id
  FROM stores s
 WHERE s.tenant_id = po.tenant_id AND s.store_code = po.branch_id AND po.store_id IS NULL;

ALTER TABLE products          ENABLE ROW LEVEL SECURITY; ALTER TABLE products          FORCE ROW LEVEL SECURITY;
ALTER TABLE customers         ENABLE ROW LEVEL SECURITY; ALTER TABLE customers         FORCE ROW LEVEL SECURITY;
ALTER TABLE suppliers         ENABLE ROW LEVEL SECURITY; ALTER TABLE suppliers         FORCE ROW LEVEL SECURITY;
ALTER TABLE purchase_order    ENABLE ROW LEVEL SECURITY; ALTER TABLE purchase_order    FORCE ROW LEVEL SECURITY;
ALTER TABLE deal              ENABLE ROW LEVEL SECURITY; ALTER TABLE deal              FORCE ROW LEVEL SECURITY;
ALTER TABLE sales_transactions ENABLE ROW LEVEL SECURITY; ALTER TABLE sales_transactions FORCE ROW LEVEL SECURITY;

-- Outstanding publications for the two deleted listeners would be republished
-- on every restart against classes that no longer exist.
DELETE FROM event_publication
 WHERE listener_id LIKE '%DealsSeedListener%' OR listener_id LIKE '%ProcurementLedgerSeedListener%';

-- ---------------------------------------------------------------------------
--  product_prices
--
--  What the customer has decided: a list price and/or a cost, per item, at one
--  branch or for the whole tenant (store_id NULL), effective from a date. Never
--  updated - a new decision is a new row - so the history is the audit trail
--  and "current" is the latest effective row, store-specific winning over
--  tenant-wide. basis is the wizard's explanation, kept verbatim.
-- ---------------------------------------------------------------------------
CREATE TABLE product_prices (
    id              uuid          PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id       uuid          NOT NULL REFERENCES tenants (id)   ON DELETE CASCADE,
    product_id      uuid          NOT NULL REFERENCES products (id)  ON DELETE CASCADE,
    store_id        uuid                   REFERENCES stores (id)    ON DELETE CASCADE,
    list_price      numeric(14,4),
    cost            numeric(14,4),
    currency        text          NOT NULL,
    effective_from  date          NOT NULL,
    source          text          NOT NULL,
    basis           jsonb,
    set_by          uuid                   REFERENCES users (id)     ON DELETE SET NULL,
    write_id        uuid,
    import_batch_id uuid,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    version         bigint        NOT NULL DEFAULT 0,

    CONSTRAINT product_prices_source_ck CHECK (source IN ('import', 'wizard', 'manual', 'applied', 'sample')),
    CONSTRAINT product_prices_any_ck    CHECK (list_price IS NOT NULL OR cost IS NOT NULL),
    CONSTRAINT product_prices_nonneg_ck CHECK ((list_price IS NULL OR list_price >= 0) AND (cost IS NULL OR cost >= 0))
);

CREATE INDEX product_prices_current_idx ON product_prices (tenant_id, product_id, store_id, effective_from DESC, created_at DESC);
CREATE INDEX product_prices_batch_idx   ON product_prices (tenant_id, import_batch_id);
CREATE INDEX product_prices_write_idx   ON product_prices (tenant_id, write_id);

CREATE TRIGGER product_prices_touch_updated_at
    BEFORE UPDATE ON product_prices
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('product_prices');

COMMENT ON TABLE  product_prices IS 'The price list: list price and cost per item (per branch, or tenant-wide when store_id is null), append-only.';
COMMENT ON COLUMN product_prices.basis IS 'How a suggested price was built, rendered verbatim by the wizard and the Sell "why" panel.';

-- ---------------------------------------------------------------------------
--  inventory_positions
-- ---------------------------------------------------------------------------
CREATE TABLE inventory_positions (
    id              uuid          PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id       uuid          NOT NULL REFERENCES tenants (id)  ON DELETE CASCADE,
    product_id      uuid          NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    store_id        uuid                   REFERENCES stores (id)   ON DELETE CASCADE,
    on_hand         numeric(14,4) NOT NULL,
    as_of           date          NOT NULL,
    source          text          NOT NULL DEFAULT 'import',
    import_batch_id uuid,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    version         bigint        NOT NULL DEFAULT 0,

    CONSTRAINT inventory_positions_on_hand_ck CHECK (on_hand >= 0),
    CONSTRAINT inventory_positions_source_ck  CHECK (source IN ('import', 'sample', 'manual'))
);

CREATE UNIQUE INDEX inventory_positions_tenant_pair_uk
    ON inventory_positions (tenant_id, product_id, store_id) NULLS NOT DISTINCT;
CREATE INDEX inventory_positions_batch_idx ON inventory_positions (tenant_id, import_batch_id);

CREATE TRIGGER inventory_positions_touch_updated_at
    BEFORE UPDATE ON inventory_positions
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('inventory_positions');

COMMENT ON TABLE inventory_positions IS 'Stock on hand per item and branch, as of a date. One row per pair; a new count replaces it.';

-- ---------------------------------------------------------------------------
--  competitor_prices
-- ---------------------------------------------------------------------------
CREATE TABLE competitor_prices (
    id              uuid          PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id       uuid          NOT NULL REFERENCES tenants (id)  ON DELETE CASCADE,
    product_id      uuid          NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    competitor      text          NOT NULL,
    price           numeric(14,4) NOT NULL,
    currency        text          NOT NULL,
    region_key      text,
    store_id        uuid                   REFERENCES stores (id)   ON DELETE SET NULL,
    observed_at     date          NOT NULL,
    source_url      text,
    source          text          NOT NULL DEFAULT 'import',
    import_batch_id uuid,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    version         bigint        NOT NULL DEFAULT 0,

    CONSTRAINT competitor_prices_competitor_ck CHECK (length(btrim(competitor)) BETWEEN 1 AND 120),
    CONSTRAINT competitor_prices_price_ck      CHECK (price >= 0),
    CONSTRAINT competitor_prices_region_ck     CHECK (region_key IS NULL OR region_key IN ('south', 'west', 'north', 'east')),
    CONSTRAINT competitor_prices_source_ck     CHECK (source IN ('import', 'sample', 'manual'))
);

CREATE INDEX competitor_prices_tenant_product_idx ON competitor_prices (tenant_id, product_id, observed_at DESC);
CREATE INDEX competitor_prices_batch_idx          ON competitor_prices (tenant_id, import_batch_id);
-- The same observation twice (a re-upload) replaces rather than duplicates.
CREATE UNIQUE INDEX competitor_prices_obs_uk ON competitor_prices (
    tenant_id, product_id, lower(competitor),
    coalesce(store_id, '00000000-0000-0000-0000-000000000000'::uuid),
    coalesce(region_key, ''), observed_at);

CREATE TRIGGER competitor_prices_touch_updated_at
    BEFORE UPDATE ON competitor_prices
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('competitor_prices');

COMMENT ON TABLE competitor_prices IS 'Prices the tenant observed at competitors. Never fetched by the platform; only what a file or a person supplied.';

-- ---------------------------------------------------------------------------
--  pricing_benchmarks (reference data, same footing as commodities)
--
--  Typical distributor gross-margin bands per category. Loaded from
--  seed/pricing-benchmarks.json at boot. Labelled "benchmark" everywhere it is
--  shown, because it is an industry reference, not the tenant's own number.
-- ---------------------------------------------------------------------------
CREATE TABLE pricing_benchmarks (
    category          text         NOT NULL,
    subcategory       text         NOT NULL DEFAULT '',
    target_margin_pct numeric(6,2) NOT NULL,
    low_margin_pct    numeric(6,2) NOT NULL,
    high_margin_pct   numeric(6,2) NOT NULL,
    note              text         NOT NULL DEFAULT '',
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),

    PRIMARY KEY (category, subcategory),
    CONSTRAINT pricing_benchmarks_band_ck CHECK (
        low_margin_pct >= 0 AND low_margin_pct <= target_margin_pct
        AND target_margin_pct <= high_margin_pct AND high_margin_pct < 100)
);

CREATE TRIGGER pricing_benchmarks_touch_updated_at
    BEFORE UPDATE ON pricing_benchmarks
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

COMMENT ON TABLE pricing_benchmarks IS 'Reference gross-margin bands by category and subcategory; row (''*'', '''') is the default.';
