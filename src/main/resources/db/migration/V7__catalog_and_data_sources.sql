-- ============================================================================
--  V7  Catalogue, reference data and data sources
--
--  Two kinds of table arrive here, and the distinction matters for RLS:
--
--    Reference   regions, subdivisions, logistics_origins, logistics_lanes,
--                commodities. Identical for every tenant, keyed by their natural
--                key, upserted from seed/*.json at startup. No tenant_id, no RLS.
--
--    Catalogue   stores, products, product_stores, customers. Tenant-scoped,
--                filled by the sample provisioner or a later import/ERP sync.
--
--    data_sources  what a tenant connected; its absence gates the workspace.
--
--  Conventions from V1 apply to every tenant-scoped table: uuid v7 keys,
--  tenant_id first in every composite index, created_at / updated_at / version,
--  updated_at maintained by trigger, and app.enable_tenant_rls().
--
--  Nothing here references a table from V5, V6 or V8: those migrations belong
--  to other builders and may run before or after this one.
-- ============================================================================

-- ---------------------------------------------------------------------------
--  Reference: market regions and their subdivisions, per country
--
--  The four market regions a commercial team talks about (south/west/north/
--  east) and the US states or UK regions each one covers. Mirrors
--  seed/countries.json, which mirrors the frontend locale.ts.
-- ---------------------------------------------------------------------------
CREATE TABLE regions (
    country_code varchar(2)  NOT NULL,
    region_key   text        NOT NULL,
    label        text        NOT NULL,
    short_label  text        NOT NULL,
    position     integer     NOT NULL,
    updated_at   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT regions_pk         PRIMARY KEY (country_code, region_key),
    CONSTRAINT regions_country_ck CHECK (country_code IN ('US', 'UK'))
);

CREATE TABLE subdivisions (
    country_code varchar(2)  NOT NULL,
    code         text        NOT NULL,
    name         text        NOT NULL,
    region_key   text        NOT NULL,
    position     integer     NOT NULL,
    updated_at   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT subdivisions_pk        PRIMARY KEY (country_code, code),
    CONSTRAINT subdivisions_region_fk FOREIGN KEY (country_code, region_key)
        REFERENCES regions (country_code, region_key) ON DELETE CASCADE
);

CREATE INDEX subdivisions_region_idx ON subdivisions (country_code, region_key, position);

COMMENT ON TABLE regions      IS 'Reference. The four market regions of a country; not tenant-scoped.';
COMMENT ON TABLE subdivisions IS 'Reference. US states / UK regions and the market region each belongs to.';

-- ---------------------------------------------------------------------------
--  Reference: the logistics rate card
--
--  Stated rates, not a model. An origin says where goods enter the network and
--  what inbound freight and duty cost; a lane says what the inland haul from
--  that point of entry to a destination region costs. Edit a row, the landed
--  cost follows. Mirrors seed/logistics.json and the frontend logistics.ts.
--
--  logistics_lanes is one row per (region, point of entry) as the blueprint
--  asks, so a single lane can be edited on its own; the region label and
--  states are repeated on each of its four rows rather than split into a
--  third table nobody would otherwise read.
-- ---------------------------------------------------------------------------
CREATE TABLE logistics_origins (
    country      text          NOT NULL,
    entry        text          NOT NULL,
    mode         text          NOT NULL,
    gateway      text          NOT NULL,
    inbound_pct  numeric(6,2)  NOT NULL,
    inbound_days integer       NOT NULL,
    duty_pct     numeric(6,2)  NOT NULL,
    duty_note    text          NOT NULL,
    position     integer       NOT NULL,
    updated_at   timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT logistics_origins_pk       PRIMARY KEY (country),
    CONSTRAINT logistics_origins_entry_ck CHECK (entry IN ('west', 'east', 'gulf', 'domestic')),
    CONSTRAINT logistics_origins_mode_ck  CHECK (mode IN ('domestic', 'overland', 'ocean'))
);

CREATE TABLE logistics_lanes (
    region_key   text          NOT NULL,
    region_label text          NOT NULL,
    states       text[]        NOT NULL,
    position     integer       NOT NULL,
    entry        text          NOT NULL,
    inland_pct   numeric(6,2)  NOT NULL,
    inland_days  integer       NOT NULL,
    updated_at   timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT logistics_lanes_pk       PRIMARY KEY (region_key, entry),
    CONSTRAINT logistics_lanes_entry_ck CHECK (entry IN ('west', 'east', 'gulf', 'domestic'))
);

COMMENT ON TABLE logistics_origins IS 'Reference. Inbound freight and duty by origin country; not tenant-scoped.';
COMMENT ON TABLE logistics_lanes   IS 'Reference. Inland haul from each point of entry to a destination region.';

-- ---------------------------------------------------------------------------
--  Reference: commodity trend
--
--  Where each underlying commodity is heading over the next quarter, as a
--  percent move in input cost. One row per commodity so every forecast on
--  every screen tells the same story about copper. Replaced by a market-data
--  feed later; the shape does not change.
-- ---------------------------------------------------------------------------
CREATE TABLE commodities (
    commodity_key text          NOT NULL,
    label         text          NOT NULL,
    pct90         numeric(6,2)  NOT NULL,
    updated_at    timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT commodities_pk PRIMARY KEY (commodity_key)
);

COMMENT ON TABLE commodities IS 'Reference. 90-day expected input-cost move per commodity; not tenant-scoped.';

-- ---------------------------------------------------------------------------
--  stores
--
--  A branch. store_code is the id the frontend, the ERP and the people quote
--  ("Dallas #100959"); the uuid is ours. subdivision_code is the US state or
--  UK region, which is what places the branch in a market region. map_x/map_y
--  are where the dot sits on the country map and nothing else.
-- ---------------------------------------------------------------------------
CREATE TABLE stores (
    id               uuid         PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id        uuid         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    store_code       text         NOT NULL,
    company_number   text,
    legal_name       text         NOT NULL,
    country          varchar(2)   NOT NULL,
    subdivision_code text,
    msa_name         text,
    rpp              numeric(6,1),
    txns             integer,
    item_count       integer,
    segment          text,
    region_key       text         NOT NULL,
    map_x            integer,
    map_y            integer,
    map_anchor       text,
    active           boolean      NOT NULL DEFAULT true,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    version          bigint       NOT NULL DEFAULT 0,

    CONSTRAINT stores_code_ck    CHECK (length(btrim(store_code)) BETWEEN 1 AND 40),
    CONSTRAINT stores_country_ck CHECK (country IN ('US', 'UK')),
    CONSTRAINT stores_segment_ck CHECK (segment IS NULL OR segment IN ('regular', 'occasional')),
    CONSTRAINT stores_region_ck  CHECK (region_key IN ('south', 'west', 'north', 'east')),
    CONSTRAINT stores_anchor_ck  CHECK (map_anchor IS NULL OR map_anchor IN ('start', 'end'))
);

CREATE UNIQUE INDEX stores_tenant_code_uk    ON stores (tenant_id, store_code);
CREATE INDEX        stores_tenant_region_idx ON stores (tenant_id, region_key, store_code);

CREATE TRIGGER stores_touch_updated_at
    BEFORE UPDATE ON stores
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('stores');

COMMENT ON TABLE  stores IS 'A branch. store_code is what people and the ERP call it; unique per tenant.';
COMMENT ON COLUMN stores.rpp IS 'Regional price parity, 100 = national average. Null = priced nationally.';

-- ---------------------------------------------------------------------------
--  products
--
--  The item master. item_number is the ERP key and what every screen navigates
--  by; the uuid is ours. has_sales = false is a catalogued-but-never-sold item
--  that cannot be priced. default_store_code is the branch the demo story
--  opens on for this item - a code rather than a uuid because it is what the
--  wire returns (ProductOption.defaultTenant) and it must never force a join.
-- ---------------------------------------------------------------------------
CREATE TABLE products (
    id                 uuid         PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id          uuid         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    item_number        text         NOT NULL,
    description        text         NOT NULL,
    short_name         text         NOT NULL,
    category           text         NOT NULL,
    subcategory        text         NOT NULL,
    commodity          text         NOT NULL DEFAULT 'none',
    unit               text         NOT NULL DEFAULT 'each',
    has_sales          boolean      NOT NULL DEFAULT false,
    default_store_code text,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    version            bigint       NOT NULL DEFAULT 0,

    CONSTRAINT products_item_ck CHECK (length(btrim(item_number)) BETWEEN 1 AND 60)
);

CREATE UNIQUE INDEX products_tenant_item_uk      ON products (tenant_id, item_number);
CREATE INDEX        products_tenant_category_idx ON products (tenant_id, category, item_number);
-- The picker search: item number OR description, case-insensitive, substring.
CREATE INDEX        products_description_trgm    ON products USING gin (lower(description) gin_trgm_ops);

CREATE TRIGGER products_touch_updated_at
    BEFORE UPDATE ON products
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('products');

COMMENT ON TABLE  products IS 'The item master. item_number is the ERP key; unique per tenant.';
COMMENT ON COLUMN products.has_sales IS 'False = catalogued but never sold, so no price can be produced.';

-- ---------------------------------------------------------------------------
--  product_stores
--
--  Which branches have sales history for an item. A row present with
--  sells = true is what makes an (item, branch) priceable; no row means the
--  branch has never sold it. Ids rather than codes: this table is joined, and
--  the codes live one hop away on either side.
-- ---------------------------------------------------------------------------
CREATE TABLE product_stores (
    id            uuid         PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id     uuid         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    product_id    uuid         NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    store_id      uuid         NOT NULL REFERENCES stores (id)   ON DELETE CASCADE,
    sells         boolean      NOT NULL DEFAULT true,
    first_sale_at date,
    last_sale_at  date,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    version       bigint       NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX product_stores_tenant_pair_uk  ON product_stores (tenant_id, product_id, store_id);
CREATE INDEX        product_stores_tenant_store_idx ON product_stores (tenant_id, store_id);

CREATE TRIGGER product_stores_touch_updated_at
    BEFORE UPDATE ON product_stores
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('product_stores');

COMMENT ON TABLE product_stores IS 'Which branches have sales history for an item. Presence + sells = priceable.';

-- ---------------------------------------------------------------------------
--  customers
--
--  Who you are quoting. code is the id the frontend uses ("c-1"); the
--  agreed discount is the standing contract the rep is already bound by, and
--  typical_qty seeds the quantity box so the page opens on a realistic deal.
-- ---------------------------------------------------------------------------
CREATE TABLE customers (
    id                  uuid          PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id           uuid          NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    code                text          NOT NULL,
    name                text          NOT NULL,
    segment             text          NOT NULL,
    tier                varchar(1)    NOT NULL,
    agreed_discount_pct numeric(6,2)  NOT NULL DEFAULT 0,
    typical_qty         integer       NOT NULL DEFAULT 1,
    profile             text          NOT NULL,
    sla_days            integer       NOT NULL,
    note                text,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    version             bigint        NOT NULL DEFAULT 0,

    CONSTRAINT customers_code_ck     CHECK (length(btrim(code)) BETWEEN 1 AND 40),
    CONSTRAINT customers_segment_ck  CHECK (segment IN ('contractor', 'institutional', 'industrial', 'walk-in')),
    CONSTRAINT customers_tier_ck     CHECK (tier IN ('A', 'B', 'C')),
    CONSTRAINT customers_profile_ck  CHECK (profile IN ('urgent', 'value', 'enterprise', 'repeat')),
    CONSTRAINT customers_discount_ck CHECK (agreed_discount_pct BETWEEN 0 AND 100),
    CONSTRAINT customers_qty_ck      CHECK (typical_qty >= 1),
    CONSTRAINT customers_sla_ck      CHECK (sla_days >= 0)
);

CREATE UNIQUE INDEX customers_tenant_code_uk ON customers (tenant_id, code);

CREATE TRIGGER customers_touch_updated_at
    BEFORE UPDATE ON customers
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('customers');

COMMENT ON TABLE customers IS 'Accounts a rep quotes. code is the wire id; the agreed discount is contractual.';

-- ---------------------------------------------------------------------------
--  data_sources
--
--  What a tenant connected during onboarding. Its absence is what keeps the
--  workspace closed: a pricing tool with no history has nothing to recommend
--  from. config is the connector settings and is never returned on the wire.
--
--  The partial unique index is the idempotency guarantee for the sample
--  dataset: two concurrent "continue with sample data" clicks cannot both
--  succeed, whatever the service checked first.
-- ---------------------------------------------------------------------------
CREATE TABLE data_sources (
    id           uuid         PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id    uuid         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    kind         text         NOT NULL,
    label        text         NOT NULL,
    detail       text         NOT NULL DEFAULT '',
    config       jsonb,
    schedule     text,
    status       text         NOT NULL DEFAULT 'pending',
    last_sync_at timestamptz,
    connected_at timestamptz  NOT NULL,
    connected_by uuid,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    version      bigint       NOT NULL DEFAULT 0,

    CONSTRAINT data_sources_kind_ck   CHECK (kind IN ('csv', 'erp', 'warehouse', 'sample')),
    CONSTRAINT data_sources_status_ck CHECK (status IN ('pending', 'connected', 'syncing', 'error')),
    CONSTRAINT data_sources_label_ck  CHECK (length(btrim(label)) BETWEEN 1 AND 120)
);

CREATE INDEX        data_sources_tenant_idx ON data_sources (tenant_id, connected_at DESC);
CREATE UNIQUE INDEX data_sources_sample_uk  ON data_sources (tenant_id) WHERE kind = 'sample';

CREATE TRIGGER data_sources_touch_updated_at
    BEFORE UPDATE ON data_sources
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('data_sources');

COMMENT ON TABLE data_sources IS 'A connected source of history. None = workspace closed. config is never returned.';
COMMENT ON INDEX data_sources_sample_uk IS 'One sample dataset per tenant; the race-proof half of the 409 already_connected.';
