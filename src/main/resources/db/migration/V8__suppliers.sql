-- ============================================================================
--  V8  Suppliers
--
--  The supplier panel and everything the Suppliers screen reads about one
--  supplier: commercial terms, the derived rating, what buyers said, the
--  fulfilment-risk score, the six-month on-time trend, and the result of
--  "add from the web" lookups.
--
--  Seven tables. The three that are one-to-one with a supplier (terms, rating,
--  risk) use supplier_id as their primary key rather than a fresh uuid: there
--  is exactly one row per supplier by definition, and a second key would only
--  be something to keep unique.
--
--  Conventions from V1 apply: uuid v7 primary keys, tenant_id first in every
--  composite index, created_at / updated_at / version on every row, and
--  updated_at maintained by trigger. Row-level security is enabled on all
--  seven; the policies compare against app.current_tenant() once
--  aatlas.rls.enabled is on.
-- ============================================================================

-- ---------------------------------------------------------------------------
--  suppliers
--
--  The seeded panel plus suppliers added from a lookup, in one table, told
--  apart by is_custom. supplier_key is the id the frontend uses ('sup-2',
--  'cus-f26j4f'): every seeded figure the Buy and Suppliers screens derive for
--  a supplier - defect rate, stock position, risk, terms - is a hash of that
--  key, so it has to be stored and stable rather than replaced by the uuid.
-- ---------------------------------------------------------------------------
CREATE TABLE suppliers (
    id               uuid             PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id        uuid             NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    supplier_key     text             NOT NULL,
    vendor_code      text             NOT NULL,
    name             text             NOT NULL,
    country          text             NOT NULL,
    city             text             NOT NULL DEFAULT '',
    website          text             NOT NULL DEFAULT '',
    category         text             NOT NULL,
    contact_name     text             NOT NULL,
    email            text             NOT NULL,
    currency         varchar(3)       NOT NULL,
    lead_time_days   integer          NOT NULL,
    otif_pct         double precision NOT NULL,
    price_index      double precision NOT NULL,
    defect_pct       double precision NOT NULL,
    holds_stock      boolean          NOT NULL,
    years_trading    integer          NOT NULL,
    spend_share_12m  double precision NOT NULL DEFAULT 0,
    spend_ytd        numeric(14,4)    NOT NULL DEFAULT 0,
    po_count_12m     integer          NOT NULL DEFAULT 0,
    is_custom        boolean          NOT NULL DEFAULT false,
    added_by         uuid             REFERENCES users (id) ON DELETE SET NULL,
    added_at         timestamptz,
    since            date             NOT NULL,
    created_at       timestamptz      NOT NULL DEFAULT now(),
    updated_at       timestamptz      NOT NULL DEFAULT now(),
    version          bigint           NOT NULL DEFAULT 0,

    CONSTRAINT suppliers_name_ck      CHECK (length(btrim(name)) BETWEEN 1 AND 200),
    CONSTRAINT suppliers_key_ck       CHECK (length(supplier_key) BETWEEN 1 AND 80),
    CONSTRAINT suppliers_category_ck  CHECK (category IN (
        'Copper & brass', 'Valves', 'Polymers', 'Steel', 'Tooling', 'Fittings')),
    CONSTRAINT suppliers_lead_ck      CHECK (lead_time_days >= 0),
    CONSTRAINT suppliers_otif_ck      CHECK (otif_pct BETWEEN 0 AND 100),
    CONSTRAINT suppliers_defect_ck    CHECK (defect_pct >= 0),
    CONSTRAINT suppliers_custom_ck    CHECK (NOT is_custom OR added_at IS NOT NULL)
);

-- The by-key lookup every /suppliers/{id} call does, and what stops a lookup
-- result being added to the same panel twice.
CREATE UNIQUE INDEX suppliers_tenant_key_uk ON suppliers (tenant_id, supplier_key);
-- The panel listing and the jump-to search.
CREATE INDEX suppliers_tenant_name_idx ON suppliers (tenant_id, name);

CREATE TRIGGER suppliers_touch_updated_at
    BEFORE UPDATE ON suppliers
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('suppliers');

COMMENT ON TABLE  suppliers IS 'The seeded panel plus suppliers added from a web lookup, in one table.';
COMMENT ON COLUMN suppliers.supplier_key IS 'The frontend id (sup-2, cus-f26j4f). Every seeded figure for a supplier hashes this, so it is stored, not derived.';
COMMENT ON COLUMN suppliers.spend_share_12m IS 'Share of the last twelve months of spend, 0-1. Zero for a supplier added today.';
COMMENT ON COLUMN suppliers.is_custom IS 'Added through a lookup. Only these can be removed from the panel.';

-- ---------------------------------------------------------------------------
--  supplier_terms
--
--  What the paperwork says: credit, early-settlement discount, the late clause
--  and its cap, warranty, quote validity, incoterm, invoice accuracy and
--  capacity - the CommercialTerms shape from the frontend's terms.ts - plus
--  the four order-mechanics columns the side-by-side compare reads (MOQ,
--  order multiple, quality PPM, response hours) and the certifications shown
--  on the profile.
-- ---------------------------------------------------------------------------
CREATE TABLE supplier_terms (
    supplier_id                uuid             PRIMARY KEY REFERENCES suppliers (id) ON DELETE CASCADE,
    tenant_id                  uuid             NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    credit_days                integer          NOT NULL,
    terms_label                text             NOT NULL,
    early_pay_discount_pct     double precision NOT NULL DEFAULT 0,
    early_pay_days             integer          NOT NULL DEFAULT 0,
    late_penalty_pct_per_week  double precision NOT NULL DEFAULT 0,
    late_penalty_cap_pct       double precision NOT NULL DEFAULT 0,
    warranty_months            integer          NOT NULL,
    quote_validity_days        integer          NOT NULL,
    incoterm                   text             NOT NULL,
    invoice_accuracy_pct       double precision NOT NULL,
    capacity_units_month       integer          NOT NULL,
    moq                        integer          NOT NULL DEFAULT 1,
    order_multiple             integer          NOT NULL DEFAULT 1,
    quality_ppm                integer          NOT NULL DEFAULT 0,
    response_hours             integer          NOT NULL DEFAULT 0,
    certifications             text[]           NOT NULL DEFAULT '{}',
    created_at                 timestamptz      NOT NULL DEFAULT now(),
    updated_at                 timestamptz      NOT NULL DEFAULT now(),
    version                    bigint           NOT NULL DEFAULT 0,

    CONSTRAINT supplier_terms_credit_ck   CHECK (credit_days >= 0),
    CONSTRAINT supplier_terms_epd_ck      CHECK (early_pay_discount_pct >= 0 AND early_pay_days >= 0),
    CONSTRAINT supplier_terms_penalty_ck  CHECK (late_penalty_pct_per_week >= 0 AND late_penalty_cap_pct >= 0),
    CONSTRAINT supplier_terms_capacity_ck CHECK (capacity_units_month >= 0),
    CONSTRAINT supplier_terms_moq_ck      CHECK (moq >= 1 AND order_multiple >= 1)
);

CREATE INDEX supplier_terms_tenant_idx ON supplier_terms (tenant_id, supplier_id);

CREATE TRIGGER supplier_terms_touch_updated_at
    BEFORE UPDATE ON supplier_terms
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('supplier_terms');

COMMENT ON TABLE  supplier_terms IS 'Commercial terms per supplier. Everything the side-by-side compare and the terms panel read.';
COMMENT ON COLUMN supplier_terms.terms_label IS 'As terms are written: "Net 45", "2/10 net 30", "Proforma". Recomputed whenever the numbers change.';

-- ---------------------------------------------------------------------------
--  supplier_ratings
--
--  Stars, derived from what the platform already measures (on-time record,
--  lead time and stock, price index, defect rate) plus a seeded communication
--  score. Stored rather than derived on read so the panel is a sorted scan;
--  the nightly engine pass recomputes it.
-- ---------------------------------------------------------------------------
CREATE TABLE supplier_ratings (
    supplier_id    uuid             PRIMARY KEY REFERENCES suppliers (id) ON DELETE CASCADE,
    tenant_id      uuid             NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    rating         double precision NOT NULL,
    review_count   integer          NOT NULL,
    quality        double precision NOT NULL,
    delivery       double precision NOT NULL,
    communication  double precision NOT NULL,
    pricing        double precision NOT NULL,
    label          text             NOT NULL,
    rating_source  text             NOT NULL,
    computed_at    timestamptz      NOT NULL,
    created_at     timestamptz      NOT NULL DEFAULT now(),
    updated_at     timestamptz      NOT NULL DEFAULT now(),
    version        bigint           NOT NULL DEFAULT 0,

    CONSTRAINT supplier_ratings_rating_ck CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT supplier_ratings_label_ck  CHECK (label IN ('Excellent', 'Good', 'Fair', 'Weak'))
);

-- The panel is ranked by rating; this is that sort.
CREATE INDEX supplier_ratings_tenant_rating_idx ON supplier_ratings (tenant_id, rating DESC);

CREATE TRIGGER supplier_ratings_touch_updated_at
    BEFORE UPDATE ON supplier_ratings
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('supplier_ratings');

COMMENT ON TABLE supplier_ratings IS 'Derived stars and breakdown per supplier. Weighted mean: delivery 0.4, communication 0.3, quality 0.15, pricing 0.15.';

-- ---------------------------------------------------------------------------
--  supplier_reviews
--
--  Three things buyers said, around the rating. when_label is kept as the
--  relative phrase the source gives ("6 weeks ago") because that is all a
--  review aggregator returns; posted_at is filled when a source gives a date.
-- ---------------------------------------------------------------------------
CREATE TABLE supplier_reviews (
    id           uuid        PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id    uuid        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    supplier_id  uuid        NOT NULL REFERENCES suppliers (id) ON DELETE CASCADE,
    position     integer     NOT NULL,
    author       text        NOT NULL,
    when_label   text        NOT NULL,
    posted_at    timestamptz,
    stars        integer     NOT NULL,
    text         text        NOT NULL,
    source       text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    version      bigint      NOT NULL DEFAULT 0,

    CONSTRAINT supplier_reviews_stars_ck CHECK (stars BETWEEN 1 AND 5)
);

CREATE INDEX supplier_reviews_tenant_supplier_idx ON supplier_reviews (tenant_id, supplier_id, position);

CREATE TRIGGER supplier_reviews_touch_updated_at
    BEFORE UPDATE ON supplier_reviews
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('supplier_reviews');

COMMENT ON TABLE supplier_reviews IS 'What other buyers said. Ordered by position, warmest first.';

-- ---------------------------------------------------------------------------
--  supplier_risk
--
--  Fulfilment risk, 0-100, with the factors the screen lists. Derived from
--  the on-time record, lead-time variance, capacity, trend and defects, and
--  from the rating. Recomputed by the same pass as the rating.
-- ---------------------------------------------------------------------------
CREATE TABLE supplier_risk (
    supplier_id         uuid             PRIMARY KEY REFERENCES suppliers (id) ON DELETE CASCADE,
    tenant_id           uuid             NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    score               integer          NOT NULL,
    level               text             NOT NULL,
    capacity            text             NOT NULL,
    lead_variance_days  double precision NOT NULL,
    consistency         text             NOT NULL,
    trend               text             NOT NULL,
    recent_delay_pct    double precision NOT NULL,
    factors             jsonb            NOT NULL,
    computed_at         timestamptz      NOT NULL,
    created_at          timestamptz      NOT NULL DEFAULT now(),
    updated_at          timestamptz      NOT NULL DEFAULT now(),
    version             bigint           NOT NULL DEFAULT 0,

    CONSTRAINT supplier_risk_score_ck       CHECK (score BETWEEN 0 AND 100),
    CONSTRAINT supplier_risk_level_ck       CHECK (level IN ('Low', 'Medium', 'High')),
    CONSTRAINT supplier_risk_capacity_ck    CHECK (capacity IN ('High', 'Medium', 'Low')),
    CONSTRAINT supplier_risk_consistency_ck CHECK (consistency IN ('High', 'Medium', 'Low')),
    CONSTRAINT supplier_risk_trend_ck       CHECK (trend IN ('Stable', 'Improving', 'Worsening'))
);

CREATE INDEX supplier_risk_tenant_idx ON supplier_risk (tenant_id, level);

CREATE TRIGGER supplier_risk_touch_updated_at
    BEFORE UPDATE ON supplier_risk
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('supplier_risk');

COMMENT ON TABLE  supplier_risk IS 'Fulfilment risk per supplier: 0 = no risk, 100 = certain trouble.';
COMMENT ON COLUMN supplier_risk.factors IS 'Rendered verbatim by the screen: [{label, value, good}].';

-- ---------------------------------------------------------------------------
--  supplier_lookups
--
--  "Pull their information from the web." The result of one lookup, kept so
--  that adding to the panel is a reference to a result the user has already
--  seen rather than a second computation that might differ. profile is the
--  SupplierProfile the screen rendered, stored verbatim.
--
--  Synchronous today (the lookup is simulated and deterministic); status is
--  here for the day it becomes an async job calling real registries.
-- ---------------------------------------------------------------------------
CREATE TABLE supplier_lookups (
    id              uuid        PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id       uuid        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    query           text        NOT NULL,
    country         text        NOT NULL,
    supplier_key    text        NOT NULL,
    profile         jsonb       NOT NULL,
    sources         jsonb       NOT NULL,
    watch_outs      text[]      NOT NULL DEFAULT '{}',
    recommendation  text        NOT NULL,
    status          text        NOT NULL DEFAULT 'complete',
    created_by      uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint      NOT NULL DEFAULT 0,

    CONSTRAINT supplier_lookups_query_ck  CHECK (length(btrim(query)) BETWEEN 1 AND 200),
    CONSTRAINT supplier_lookups_status_ck CHECK (status IN ('pending', 'complete', 'added', 'failed'))
);

-- Newest first: "what did I look up earlier".
CREATE INDEX supplier_lookups_tenant_created_idx ON supplier_lookups (tenant_id, created_at DESC);

CREATE TRIGGER supplier_lookups_touch_updated_at
    BEFORE UPDATE ON supplier_lookups
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('supplier_lookups');

COMMENT ON TABLE  supplier_lookups IS 'Results of "add from the web". Adding to the panel references one of these by id.';
COMMENT ON COLUMN supplier_lookups.supplier_key IS 'The id the profile will have on the panel (cus-...), derived from query and country.';

-- ---------------------------------------------------------------------------
--  supplier_performance_months
--
--  One row per supplier per month: the six-point on-time trend the profile
--  shows, and room for the scorecard columns once purchase orders exist.
-- ---------------------------------------------------------------------------
CREATE TABLE supplier_performance_months (
    id             uuid             PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id      uuid             NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    supplier_id    uuid             NOT NULL REFERENCES suppliers (id) ON DELETE CASCADE,
    month          date             NOT NULL,
    otif_pct       double precision NOT NULL,
    defect_pct     double precision,
    orders         integer,
    spend          numeric(14,4),
    avg_lead_days  double precision,
    created_at     timestamptz      NOT NULL DEFAULT now(),
    updated_at     timestamptz      NOT NULL DEFAULT now(),
    version        bigint           NOT NULL DEFAULT 0,

    CONSTRAINT supplier_perf_month_ck CHECK (month = date_trunc('month', month)::date),
    CONSTRAINT supplier_perf_otif_ck  CHECK (otif_pct BETWEEN 0 AND 100)
);

CREATE UNIQUE INDEX supplier_perf_tenant_supplier_month_uk
    ON supplier_performance_months (tenant_id, supplier_id, month);

CREATE TRIGGER supplier_performance_months_touch_updated_at
    BEFORE UPDATE ON supplier_performance_months
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('supplier_performance_months');

COMMENT ON TABLE  supplier_performance_months IS 'Monthly on-time record per supplier. The profile reads the last six.';
COMMENT ON COLUMN supplier_performance_months.month IS 'First day of the month.';
