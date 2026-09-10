-- ============================================================================
--  V13  Bulk actions, integrations/MCP, and the assistant
--
--  Three unrelated modules share this migration number because they are one
--  wave-2 track (see docs/decisions.md): bulk sell/buy strategies, the
--  business-systems and MCP catalogue with its per-tenant connection state,
--  and the assistant's question history.
--
--  Conventions from V1 apply: uuid v7 primary keys, tenant_id first in every
--  composite index, created_at / updated_at / version on every row, updated_at
--  maintained by trigger, row-level security enabled everywhere.
-- ============================================================================

-- ---------------------------------------------------------------------------
--  bulk_decision / bulk_deal
--
--  What "apply a bulk strategy" writes. This is a stand-in for the decisions
--  module's real recorder (TODO(merge): retarget BulkDecisionRecorder at
--  com.aatlas.decisions once that module exists) - a bulk apply is a kind of
--  decision like any other and belongs in that module's ledger, not a table
--  this module owns forever. Until then, one row per apply (bulk_decision)
--  plus one row per basket line (bulk_deal) is enough for the demo to show
--  what was applied and to browse it back.
-- ---------------------------------------------------------------------------
CREATE TABLE bulk_decision (
    id             uuid           PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id      uuid           NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    kind           text           NOT NULL,
    strategy_key   text           NOT NULL,
    target         text           NOT NULL,
    item_count     integer        NOT NULL,
    total_value    numeric(14,4)  NOT NULL,
    total_impact   numeric(14,4)  NOT NULL,
    payload        jsonb          NOT NULL,
    created_by     uuid           REFERENCES users (id) ON DELETE SET NULL,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    version        bigint         NOT NULL DEFAULT 0,

    CONSTRAINT bulk_decision_kind_ck   CHECK (kind IN ('sell', 'buy')),
    CONSTRAINT bulk_decision_target_ck CHECK (length(btrim(target)) BETWEEN 1 AND 80)
);

CREATE INDEX bulk_decision_tenant_created_idx ON bulk_decision (tenant_id, created_at DESC);

CREATE TRIGGER bulk_decision_touch_updated_at
    BEFORE UPDATE ON bulk_decision
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('bulk_decision');

COMMENT ON TABLE  bulk_decision IS 'One row per "apply a bulk strategy". Stand-in for the decisions module; TODO(merge).';
COMMENT ON COLUMN bulk_decision.target IS 'The store code for a sell decision, the region key for a buy decision.';
COMMENT ON COLUMN bulk_decision.payload IS 'The applied strategy''s projection, rendered verbatim by a history screen.';

CREATE TABLE bulk_deal (
    id              uuid           PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id       uuid           NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    decision_id     uuid           NOT NULL REFERENCES bulk_decision (id) ON DELETE CASCADE,
    item_number     text           NOT NULL,
    qty             integer        NOT NULL,
    unit_price      numeric(14,4),
    unit_cost       numeric(14,4),
    supplier_key    text,
    supplier_name   text,
    created_at      timestamptz    NOT NULL DEFAULT now(),
    updated_at      timestamptz    NOT NULL DEFAULT now(),
    version         bigint         NOT NULL DEFAULT 0,

    CONSTRAINT bulk_deal_qty_ck CHECK (qty >= 0)
);

CREATE INDEX bulk_deal_tenant_decision_idx ON bulk_deal (tenant_id, decision_id);

CREATE TRIGGER bulk_deal_touch_updated_at
    BEFORE UPDATE ON bulk_deal
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('bulk_deal');

COMMENT ON TABLE  bulk_deal IS 'One row per basket line an applied bulk strategy priced or awarded.';
COMMENT ON COLUMN bulk_deal.unit_price IS 'Sell lines: the applied price. Null for a buy line.';
COMMENT ON COLUMN bulk_deal.unit_cost IS 'Buy lines: the awarded landed cost. Null for a sell line.';

-- ---------------------------------------------------------------------------
--  assistant_question
--
--  "My recent questions" for Ask Aatlas. Minimal by design - no answer
--  payload, no session id - because nothing downstream reads more than the
--  question and when it was asked.
-- ---------------------------------------------------------------------------
CREATE TABLE assistant_question (
    id          uuid        PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id   uuid        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_id     uuid        REFERENCES users (id) ON DELETE SET NULL,
    question    text        NOT NULL,
    asked_at    timestamptz NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     bigint      NOT NULL DEFAULT 0,

    CONSTRAINT assistant_question_text_ck CHECK (length(btrim(question)) BETWEEN 1 AND 500)
);

CREATE INDEX assistant_question_tenant_user_idx ON assistant_question (tenant_id, user_id, asked_at DESC);

CREATE TRIGGER assistant_question_touch_updated_at
    BEFORE UPDATE ON assistant_question
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('assistant_question');

COMMENT ON TABLE assistant_question IS 'What a seat asked Ask Aatlas, and when. Backs GET /assistant/history.';

-- ---------------------------------------------------------------------------
--  integration_connection
--
--  Per-tenant connection state for the 15 catalogued business systems. The
--  catalogue itself (which systems exist, their category and note) is static
--  reference data compiled into the app, ported from src/lib/intel/integrations.ts's
--  INTEGRATIONS constant - only which ones a tenant has turned on lives here.
--  A tenant with no row for a key simply has not connected it ('available').
-- ---------------------------------------------------------------------------
CREATE TABLE integration_connection (
    id               uuid        PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id        uuid        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    integration_key  text        NOT NULL,
    category         text        NOT NULL,
    status           text        NOT NULL DEFAULT 'connected',
    config           jsonb       NOT NULL DEFAULT '{}'::jsonb,
    connected_at     timestamptz NOT NULL DEFAULT now(),
    connected_by     uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    version          bigint      NOT NULL DEFAULT 0,

    CONSTRAINT integration_connection_status_ck CHECK (status IN ('connected', 'available'))
);

CREATE UNIQUE INDEX integration_connection_tenant_key_uk ON integration_connection (tenant_id, integration_key);

CREATE TRIGGER integration_connection_touch_updated_at
    BEFORE UPDATE ON integration_connection
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('integration_connection');

COMMENT ON TABLE  integration_connection IS 'A tenant''s connection to one of the 15 catalogued business systems. Row absent = not connected.';
COMMENT ON COLUMN integration_connection.config IS 'Connection settings as entered. Plain jsonb, not encrypted: demo config, not a live secret (see docs/decisions.md).';

-- ---------------------------------------------------------------------------
--  mcp_client
--
--  Per-tenant connection state for the three MCP client kinds (claude /
--  gemini / other). The catalogue (name, note) is static, ported from
--  MCP_CLIENTS.
-- ---------------------------------------------------------------------------
CREATE TABLE mcp_client (
    id            uuid        PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id     uuid        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    client_key    text        NOT NULL,
    connected     boolean     NOT NULL DEFAULT false,
    connected_at  timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    version       bigint      NOT NULL DEFAULT 0,

    CONSTRAINT mcp_client_key_ck CHECK (client_key IN ('claude', 'gemini', 'other'))
);

CREATE UNIQUE INDEX mcp_client_tenant_key_uk ON mcp_client (tenant_id, client_key);

CREATE TRIGGER mcp_client_touch_updated_at
    BEFORE UPDATE ON mcp_client
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('mcp_client');

COMMENT ON TABLE mcp_client IS 'Whether a tenant has connected each MCP client kind. Row absent = not connected.';

-- ---------------------------------------------------------------------------
--  mcp_permission
--
--  Per-tenant override of the 16 catalogued MCP permissions (read / actions /
--  restricted). The catalogue (group, label, requiresApproval default,
--  defaultOn) is static, ported from MCP_PERMISSIONS; a tenant with no row
--  for a key reads the catalogue's defaultOn. requires_approval is stored so
--  a tenant could in principle tighten it above the catalogue default, though
--  today's PUT only ever changes enabled - see IntegrationsService.
-- ---------------------------------------------------------------------------
CREATE TABLE mcp_permission (
    id                 uuid        PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id          uuid        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    permission_key     text        NOT NULL,
    enabled            boolean     NOT NULL,
    requires_approval  boolean     NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    version            bigint      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX mcp_permission_tenant_key_uk ON mcp_permission (tenant_id, permission_key);

CREATE TRIGGER mcp_permission_touch_updated_at
    BEFORE UPDATE ON mcp_permission
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('mcp_permission');

COMMENT ON TABLE mcp_permission IS 'A tenant''s override of one catalogued MCP permission. Row absent = the catalogue default applies.';
