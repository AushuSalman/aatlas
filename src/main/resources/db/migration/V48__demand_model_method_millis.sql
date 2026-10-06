-- ============================================================================
--  V48 - how long each forecaster took
--
--  The Forecasting model choice in Settings shows, beside each model, how long it took in the
--  last training run and how accurate it was on the weeks held back from it. The accuracy is
--  read from the per-pair back-tests already stored; the time is recorded here, by forecaster
--  key, from the next run on.
-- ============================================================================

ALTER TABLE demand_models ADD COLUMN method_millis jsonb;

COMMENT ON COLUMN demand_models.method_millis IS
    'Milliseconds each forecaster took in the last training run, by key (forest, ses, sba, tsb, chronos).';
