-- ============================================================================
--  V44 - the forecasting model a tenant chose
--
--  The demand model scores several forecasters for every item and branch. By default each item
--  reads the one that proved itself on it; a tenant may instead name one forecaster to read
--  everywhere. Null is that default. Kept on the model's own row, apart from the pricing model's
--  settings, so a pricing preset or reset never touches it.
-- ============================================================================

ALTER TABLE demand_models ADD COLUMN forecast_model text;

COMMENT ON COLUMN demand_models.forecast_model IS
    'The forecaster the demand step reads for every item (chronos, forest, sba, tsb, ses); null for the one proven on each item.';
