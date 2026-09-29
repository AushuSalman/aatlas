-- ============================================================================
--  V35 - the competitors a tenant tracks, and prices read off their own sites
--
--  tracked_competitors: the online competitors a tenant has chosen to follow, by
--  domain. Found with DataForSEO (the domains that rank for the tenant's items) or
--  added by hand. Each active one is searched for an item on every live lookup, and
--  the price is read from that competitor's own product page (Oxylabs fetches it).
--
--  competitor_prices gains the source 'site' for those observations.
--
--  Conventions from V1: uuid v7 keys, tenant_id first, created/updated/version,
--  updated_at by trigger, app.enable_tenant_rls() for isolation.
-- ============================================================================

CREATE TABLE tracked_competitors (
    id              uuid          PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id       uuid          NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    domain          text          NOT NULL,
    name            text          NOT NULL,
    source          text          NOT NULL DEFAULT 'manual',
    active          boolean       NOT NULL DEFAULT true,
    -- What discovery said about it: average position, keywords shared, visibility, the
    -- keywords it was found for. Informational; nothing reads it for pricing.
    discovery       jsonb,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    version         bigint        NOT NULL DEFAULT 0,

    CONSTRAINT tracked_competitors_domain_ck CHECK (domain = lower(domain) AND length(domain) BETWEEN 3 AND 253),
    CONSTRAINT tracked_competitors_name_ck   CHECK (length(btrim(name)) BETWEEN 1 AND 120),
    CONSTRAINT tracked_competitors_source_ck CHECK (source IN ('dataforseo', 'manual'))
);

CREATE UNIQUE INDEX tracked_competitors_domain_uk ON tracked_competitors (tenant_id, domain);

CREATE TRIGGER tracked_competitors_touch_updated_at
    BEFORE UPDATE ON tracked_competitors
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('tracked_competitors');

COMMENT ON TABLE tracked_competitors IS
    'Online competitors a tenant follows, by domain: found with DataForSEO or added by hand. Active ones are priced from their own product pages.';

ALTER TABLE competitor_prices DROP CONSTRAINT competitor_prices_source_ck;

ALTER TABLE competitor_prices ADD CONSTRAINT competitor_prices_source_ck
    CHECK (source IN ('import', 'sample', 'manual', 'serpapi', 'ebay', 'rainforest', 'oxylabs', 'site'));
