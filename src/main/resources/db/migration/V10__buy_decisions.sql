-- ============================================================================
--  V10  Buy decisions
--
--  One table: the record `POST /buy/select` writes when a buyer commits to a
--  procurement option. This is a STAND-IN for the `decisions` module's own
--  table (Track D / History-Analytics, a different worktree, not yet built).
--  TODO(merge): retarget POST /buy/select at the `decisions` module's real
--  writer once it exists, and drop this table (or keep it as a buy-side index
--  onto that module's rows - the lead's call at merge time).
--
--  Conventions from V1 apply: uuid v7 primary key, tenant_id first in the
--  index, created_at / updated_at / version, updated_at maintained by
--  trigger, row-level security enabled.
-- ============================================================================

CREATE TABLE buy_decisions (
    id                    uuid          PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id             uuid          NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    item_number           text          NOT NULL,
    region_key            text          NOT NULL,
    destination_store_code text         NOT NULL,
    option_key            text          NOT NULL,
    order_value           numeric(14,4) NOT NULL,
    status                text          NOT NULL,
    decided_by            uuid          REFERENCES users (id) ON DELETE SET NULL,
    decided_at            timestamptz   NOT NULL,
    approver_role         text,
    approve_limit         numeric(14,4),
    created_at            timestamptz   NOT NULL DEFAULT now(),
    updated_at            timestamptz   NOT NULL DEFAULT now(),
    version               bigint        NOT NULL DEFAULT 0,

    CONSTRAINT buy_decisions_item_ck   CHECK (length(btrim(item_number)) BETWEEN 1 AND 40),
    CONSTRAINT buy_decisions_status_ck CHECK (status IN ('recorded', 'pending_approval')),
    CONSTRAINT buy_decisions_value_ck  CHECK (order_value >= 0)
);

-- The history a tenant would browse: newest decisions first.
CREATE INDEX buy_decisions_tenant_decided_idx ON buy_decisions (tenant_id, decided_at DESC);

CREATE TRIGGER buy_decisions_touch_updated_at
    BEFORE UPDATE ON buy_decisions
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('buy_decisions');

COMMENT ON TABLE buy_decisions IS
    'What POST /buy/select recorded. Stand-in for the decisions module''s own table (TODO(merge)).';
COMMENT ON COLUMN buy_decisions.status IS
    '''recorded'': the seat could commit alone. ''pending_approval'': over the seat''s approval limit, sent up.';
