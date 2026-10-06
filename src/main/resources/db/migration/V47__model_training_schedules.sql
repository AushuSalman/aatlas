-- ============================================================================
--  V47 - when each trained model retrains
--
--  The demand and delivery models used to retrain at one fixed time every night. Each tenant
--  now chooses: daily, weekly or monthly at a local time, or only on request - and optionally
--  as soon as enough new rows (sales from finished weeks, received orders) have come in. A
--  tenant with no row keeps the old nightly time; its row is written the first time the
--  scheduler sees it.
-- ============================================================================

CREATE TABLE model_training_schedules (
    tenant_id            uuid          NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    model                text          NOT NULL,
    frequency            text          NOT NULL,
    at_time              time          NOT NULL,
    weekday              integer,
    month_day            integer,
    time_zone            text          NOT NULL DEFAULT 'UTC',
    retrain_on_new_data  boolean       NOT NULL DEFAULT false,
    min_new_rows         integer       NOT NULL DEFAULT 50,
    next_run_at          timestamptz,
    last_run_at          timestamptz,
    last_result          text,
    last_note            text,
    updated_by           uuid,
    created_at           timestamptz   NOT NULL DEFAULT now(),
    updated_at           timestamptz   NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, model),
    CONSTRAINT model_training_schedules_model_ck     CHECK (model IN ('demand', 'delivery')),
    CONSTRAINT model_training_schedules_freq_ck      CHECK (frequency IN ('daily', 'weekly', 'monthly', 'manual')),
    CONSTRAINT model_training_schedules_weekday_ck   CHECK (weekday IS NULL OR weekday BETWEEN 1 AND 7),
    CONSTRAINT model_training_schedules_monthday_ck  CHECK (month_day IS NULL OR month_day BETWEEN 1 AND 28),
    CONSTRAINT model_training_schedules_rows_ck      CHECK (min_new_rows BETWEEN 1 AND 100000),
    CONSTRAINT model_training_schedules_result_ck    CHECK (last_result IS NULL
                                                            OR last_result IN ('trained', 'not-trained', 'failed'))
);

CREATE TRIGGER model_training_schedules_touch_updated_at
    BEFORE UPDATE ON model_training_schedules
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('model_training_schedules');

COMMENT ON TABLE model_training_schedules IS
    'When each trained model (demand, delivery) retrains per tenant: daily/weekly/monthly at a local time or manual, optionally on new data.';
