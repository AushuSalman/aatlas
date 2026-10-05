-- ============================================================================
--  V45 - the last Retail and bulk check per item
--
--  The whole check as the page showed it - both sides, every listing kept or
--  dropped and why, with when it was fetched - so the next visit to the item
--  opens on it instead of an empty card. One row per item, replaced by every
--  check. The bulk per-unit summary the buy recommendation reads stays in
--  buy_market_benchmarks.
-- ============================================================================

CREATE TABLE buy_market_checks (
    tenant_id   uuid         NOT NULL REFERENCES tenants (id)  ON DELETE CASCADE,
    product_id  uuid         NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    query       text         NOT NULL,
    checked_at  timestamptz  NOT NULL,
    result      jsonb        NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, product_id)
);

CREATE TRIGGER buy_market_checks_touch_updated_at
    BEFORE UPDATE ON buy_market_checks
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('buy_market_checks');

COMMENT ON TABLE buy_market_checks IS
    'The latest Retail and bulk check per item (all listings, as shown), so the buy page reopens on it with its fetch time.';
