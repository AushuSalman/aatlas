-- ============================================================================
--  V39 - daily competitor-price refresh
--
--  A scheduled job re-checks every product's competitor prices once a day for each
--  tenant that has chosen its price sources, so the prices Sell shows and the
--  recommendations start from never go stale. A tenant can switch it off.
-- ============================================================================

ALTER TABLE competition_settings ADD COLUMN daily_refresh boolean NOT NULL DEFAULT true;

COMMENT ON COLUMN competition_settings.daily_refresh IS
    'Re-check every product''s competitor prices once a day with the enabled sources.';

ALTER TABLE competitor_refresh_jobs DROP CONSTRAINT competitor_refresh_jobs_trigger_ck;

ALTER TABLE competitor_refresh_jobs ADD CONSTRAINT competitor_refresh_jobs_trigger_ck
    CHECK (trigger IN ('setup', 'import', 'manual', 'daily'));
