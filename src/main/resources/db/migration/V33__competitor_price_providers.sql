-- ============================================================================
--  V33 - live competitor prices
--
--  The competition module fetches competitor prices from shopping-data providers and
--  writes the listings it keeps into competitor_prices, with the provider as the row's
--  source and the listing's URL as source_url - as citable as an imported row.
-- ============================================================================

ALTER TABLE competitor_prices DROP CONSTRAINT competitor_prices_source_ck;

ALTER TABLE competitor_prices ADD CONSTRAINT competitor_prices_source_ck
    CHECK (source IN ('import', 'sample', 'manual', 'serpapi', 'ebay', 'rainforest', 'oxylabs'));

COMMENT ON COLUMN competitor_prices.source IS
    'import, sample or manual; or the provider a live lookup used: serpapi (Google Shopping), ebay, rainforest (Amazon), oxylabs (Google Shopping).';

COMMENT ON TABLE competitor_prices IS
    'Prices observed at competitors: from a file, a person, or a live shopping-data lookup (source names which). Every row keeps its source URL where it has one.';
