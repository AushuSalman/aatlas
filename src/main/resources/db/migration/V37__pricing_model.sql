-- ============================================================================
--  V37  Pricing model: per-tenant toggles and knobs on the recommendation chain
--
--    pricing_model_settings   a tenant's overrides, keyed by PricingModel parameter key
--    pricing_model_history    who changed them, to what, when
--
--  The registry of parameters (every step the chain can take, its default, and
--  the range of each knob) lives in code: history.PricingModel. The database
--  holds only what a tenant changed, as one jsonb map of
--    {"<key>": {"on": bool|null, "value": number|null}, ...}
--  so a parameter added or retired in code needs no migration - an override
--  for a key that no longer exists is dropped on read.
--
--  Conventions from V1: tenant-keyed, created/updated/version, updated_at by
--  trigger, app.enable_tenant_rls() for isolation. Mirrors V6's guardrails.
-- ============================================================================

-- ---------------------------------------------------------------------------
--  pricing_model_settings
--
--  One row per tenant, and only once someone has saved: a tenant with no row
--  is on the registry defaults, which is what PricingModel.Config.defaults()
--  answers. The row's identity is the tenant.
-- ---------------------------------------------------------------------------
CREATE TABLE pricing_model_settings (
    tenant_id   uuid        PRIMARY KEY REFERENCES tenants (id) ON DELETE CASCADE,
    settings    jsonb       NOT NULL DEFAULT '{}'::jsonb,
    updated_by  uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     bigint      NOT NULL DEFAULT 0,

    CONSTRAINT pricing_model_settings_object_ck CHECK (jsonb_typeof(settings) = 'object')
);

CREATE TRIGGER pricing_model_settings_touch_updated_at
    BEFORE UPDATE ON pricing_model_settings
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('pricing_model_settings');

COMMENT ON TABLE pricing_model_settings IS
    'A tenant''s pricing-model overrides, keyed by PricingModel parameter key; only what differs from the registry defaults is stored.';
COMMENT ON COLUMN pricing_model_settings.settings IS
    '{"<key>": {"on": bool|null, "value": number|null}}: a toggle carries on, a number carries value. No row or {} = the defaults.';

-- ---------------------------------------------------------------------------
--  pricing_model_history
--
--  Append-only. The snapshot is the override map as stored after the save (or
--  {} for a reset), because the history screen renders it verbatim and nothing
--  filters on a single parameter; who and when are real columns because the
--  list is sorted and paged on them.
-- ---------------------------------------------------------------------------
CREATE TABLE pricing_model_history (
    id              uuid        PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id       uuid        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    action          text        NOT NULL,
    snapshot        jsonb       NOT NULL,
    changed_by      uuid        REFERENCES users (id) ON DELETE SET NULL,
    changed_by_role text,
    changed_at      timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint      NOT NULL DEFAULT 0,

    CONSTRAINT pricing_model_history_action_ck   CHECK (action IN ('set', 'reset')),
    CONSTRAINT pricing_model_history_snapshot_ck CHECK (jsonb_typeof(snapshot) = 'object')
);

-- Newest first, keyset-paged on the v7 id, which is time-ordered.
CREATE INDEX pricing_model_history_tenant_idx ON pricing_model_history (tenant_id, id DESC);

CREATE TRIGGER pricing_model_history_touch_updated_at
    BEFORE UPDATE ON pricing_model_history
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('pricing_model_history');

COMMENT ON TABLE pricing_model_history IS
    'Every save or reset of a tenant''s pricing-model overrides, with the override map as stored.';
