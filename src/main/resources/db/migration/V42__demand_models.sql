-- ============================================================================
--  V42 - the demand model, one per tenant
--
--  A machine-learned forecaster (a random forest of regression trees) fitted nightly to the
--  tenant's own weekly sales per item and branch. The row holds the model's bytes, the per-pair
--  report and forecasting context (pairs), and what the run was fitted on. A row with a null
--  model is a tenant the trainer visited and could not fit; note says why.
-- ============================================================================

CREATE TABLE demand_models (
    tenant_id     uuid        PRIMARY KEY REFERENCES tenants (id) ON DELETE CASCADE,
    model          bytea,
    response_model bytea,
    pairs         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    trained_at    timestamptz,
    row_count     integer     NOT NULL DEFAULT 0,
    pair_count    integer     NOT NULL DEFAULT 0,
    weeks         integer     NOT NULL DEFAULT 0,
    from_week     date,
    to_week       date,
    holdout_weeks integer     NOT NULL DEFAULT 8,
    train_millis  bigint      NOT NULL DEFAULT 0,
    note          text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    version       bigint      NOT NULL DEFAULT 0,
    CONSTRAINT demand_models_pairs_ck CHECK (jsonb_typeof(pairs) = 'object')
);

CREATE TRIGGER demand_models_touch_updated_at
    BEFORE UPDATE ON demand_models
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('demand_models');

COMMENT ON TABLE demand_models IS
    'A tenant''s trained demand model (Tribuo random forest, protobuf bytes) with its per-pair report and forecasting context.';
COMMENT ON COLUMN demand_models.model IS
    'The forecast model (sees the previous weeks'' demand): units next week.';
COMMENT ON COLUMN demand_models.response_model IS
    'The response model (no lags): settled units at a price, and the probed price sensitivity.';
COMMENT ON COLUMN demand_models.pairs IS
    'Keyed by item@store: the pair''s last week, price, cost, competitor median and lags (what a forecast starts from) and how the run scored it (held-out error against the baseline, usable, elasticity).';
COMMENT ON COLUMN demand_models.row_count IS
    'Weekly training rows the last run used.';
COMMENT ON COLUMN demand_models.note IS
    'What the last run concluded, in words: how many pairs beat the baseline, or why it could not train.';
