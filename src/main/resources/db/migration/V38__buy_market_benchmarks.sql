-- ============================================================================
--  V38 - market benchmarks for buying
--
--  What a unit costs in bulk on the open market: the median and lowest per-unit
--  price of the lots and cases the "Retail and bulk check" kept (a lot of 50 at
--  $150 is $3.00 a unit). One row per item and kind, replaced on every check, so
--  the buy recommendation can count it as market evidence next to the supplier
--  panel's quotes and the should-cost read off competitors' shop prices.
-- ============================================================================

CREATE TABLE buy_market_benchmarks (
    tenant_id        uuid           NOT NULL REFERENCES tenants (id)  ON DELETE CASCADE,
    product_id       uuid           NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    kind             text           NOT NULL,
    median_per_unit  numeric(14,4)  NOT NULL,
    low_per_unit     numeric(14,4)  NOT NULL,
    listings         integer        NOT NULL,
    sources          text           NOT NULL,
    currency         text           NOT NULL,
    observed_at      date           NOT NULL,
    created_at       timestamptz    NOT NULL DEFAULT now(),
    updated_at       timestamptz    NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, product_id, kind),
    CONSTRAINT buy_market_benchmarks_kind_ck   CHECK (kind IN ('bulk-lots')),
    CONSTRAINT buy_market_benchmarks_price_ck  CHECK (median_per_unit > 0 AND low_per_unit > 0),
    CONSTRAINT buy_market_benchmarks_count_ck  CHECK (listings > 0)
);

CREATE TRIGGER buy_market_benchmarks_touch_updated_at
    BEFORE UPDATE ON buy_market_benchmarks
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('buy_market_benchmarks');

COMMENT ON TABLE buy_market_benchmarks IS
    'Open-market per-unit prices for buying (bulk lots), kept by the Retail and bulk check; read as market evidence by the buy recommendation.';
