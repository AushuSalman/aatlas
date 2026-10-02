-- ============================================================================
--  V43 - the delivery model, one per tenant
--
--  Machine-learned lead-time and late-delivery forecasters (two random forests of regression
--  trees) fitted nightly to the tenant's own received purchase orders. The row holds the models'
--  bytes, the per-supplier report and forecasting context (suppliers), and what the run was
--  fitted on. A row with null models is a tenant the trainer visited and could not fit; note
--  says why.
-- ============================================================================

CREATE TABLE delivery_models (
    tenant_id      uuid        PRIMARY KEY REFERENCES tenants (id) ON DELETE CASCADE,
    slip_model     bytea,
    late_model     bytea,
    suppliers      jsonb       NOT NULL DEFAULT '{}'::jsonb,
    trained_at     timestamptz,
    order_count    integer     NOT NULL DEFAULT 0,
    supplier_count integer     NOT NULL DEFAULT 0,
    from_date      date,
    to_date        date,
    holdout_orders integer     NOT NULL DEFAULT 0,
    train_millis   bigint      NOT NULL DEFAULT 0,
    note           text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    version        bigint      NOT NULL DEFAULT 0,
    CONSTRAINT delivery_models_suppliers_ck CHECK (jsonb_typeof(suppliers) = 'object')
);

CREATE TRIGGER delivery_models_touch_updated_at
    BEFORE UPDATE ON delivery_models
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('delivery_models');

COMMENT ON TABLE delivery_models IS
    'A tenant''s trained delivery models (Tribuo random forests, protobuf bytes) with the per-supplier report and forecasting context.';
COMMENT ON COLUMN delivery_models.slip_model IS
    'Predicts the slip in days: actual lead time minus promised, negative when early.';
COMMENT ON COLUMN delivery_models.late_model IS
    'Predicts late as 0/1; the forest''s average is read as the chance of a late delivery.';
COMMENT ON COLUMN delivery_models.suppliers IS
    'Keyed by supplier id: the supplier''s record after its last order (what a forecast starts from) and how the run scored it against that record.';
COMMENT ON COLUMN delivery_models.note IS
    'What the last run concluded, in words: how many suppliers the model beat the record on, or why it could not train.';
