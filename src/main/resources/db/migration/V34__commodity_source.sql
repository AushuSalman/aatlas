-- ============================================================================
--  V34 - where a commodity figure came from
--
--  The marketdata module refreshes commodities.pct90 from FRED producer price indices
--  and writes the feed and series here ("FRED WPU102502"); the shipped figure stays
--  'seed'. The startup seeder only overwrites 'seed' rows, and every explanation that
--  cites a commodity move cites this instead of a fixed "reference".
-- ============================================================================

ALTER TABLE commodities ADD COLUMN source text NOT NULL DEFAULT 'seed';

COMMENT ON COLUMN commodities.source IS
    'seed for the shipped reference figure; otherwise the live feed and series that wrote pct90, e.g. FRED WPU102502.';
