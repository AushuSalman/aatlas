-- ============================================================================
--  V19  RFQ
--
--  Three tables, per the blueprint: `rfq` (the round), `rfq_invite` (who was
--  asked, and what the panel expected from them), `rfq_quote` (one row per
--  invite that has answered - declined or quoted). `product_id`/`store_id` in
--  the blueprint's own sketch become item_number/destination_id text columns
--  here, matching V10's `buy_decisions` convention: catalog identity on the
--  wire is the ERP item number and the store code, not a row uuid this
--  module has any reason to know.
--
--  Conventions from V1 apply: uuid v7 primary key, tenant_id first in the
--  index, created_at / updated_at / version, updated_at maintained by
--  trigger, row-level security enabled.
-- ============================================================================

CREATE TABLE rfq (
    id                 uuid          PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id          uuid          NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    ref                text          NOT NULL,
    item_number        text          NOT NULL,
    item_name          text          NOT NULL,
    region_key         text          NOT NULL,
    region_label       text          NOT NULL,
    destination_id     text          NOT NULL,
    destination_label  text          NOT NULL,
    qty                integer       NOT NULL,
    required_days      integer       NOT NULL,
    urgency            text          NOT NULL,
    priority           text          NOT NULL,
    incoterm           text          NOT NULL,
    payment_terms      text          NOT NULL,
    notes              text          NOT NULL DEFAULT '',
    message            text          NOT NULL,
    status             text          NOT NULL DEFAULT 'draft',
    closes_at          timestamptz,
    awarded_supplier_id text,
    awarded_at         timestamptz,
    decision_id        uuid          REFERENCES decision (id) ON DELETE SET NULL,
    created_by         uuid          NOT NULL REFERENCES users (id) ON DELETE SET NULL,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    updated_at         timestamptz   NOT NULL DEFAULT now(),
    version            bigint        NOT NULL DEFAULT 0,

    CONSTRAINT rfq_qty_ck   CHECK (qty > 0),
    CONSTRAINT rfq_status_ck CHECK (status IN ('draft', 'sent', 'quoted', 'awarded', 'closed'))
);

CREATE INDEX rfq_tenant_created_idx ON rfq (tenant_id, created_at DESC);
CREATE INDEX rfq_tenant_status_idx ON rfq (tenant_id, status, created_at DESC);
-- One award per decision - what lets ApprovalOutcomeListener find the round a granted or
-- rejected approval belongs to.
CREATE UNIQUE INDEX rfq_decision_idx ON rfq (decision_id) WHERE decision_id IS NOT NULL;

CREATE TRIGGER rfq_touch_updated_at
    BEFORE UPDATE ON rfq
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('rfq');

-- ---------------------------------------------------------------------------

CREATE TABLE rfq_invite (
    id             uuid          PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id      uuid          NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    rfq_id         uuid          NOT NULL REFERENCES rfq (id) ON DELETE CASCADE,
    supplier_id    text          NOT NULL,
    name           text          NOT NULL,
    country        text          NOT NULL,
    route          text          NOT NULL,
    expected_landed numeric(14,4) NOT NULL,
    lead_days      integer       NOT NULL,
    on_time_pct    numeric(5,2)  NOT NULL,
    risk_level     text          NOT NULL,
    sent_at        timestamptz,
    status         text          NOT NULL DEFAULT 'invited',
    created_at     timestamptz   NOT NULL DEFAULT now(),
    updated_at     timestamptz   NOT NULL DEFAULT now(),
    version        bigint        NOT NULL DEFAULT 0,

    CONSTRAINT rfq_invite_status_ck CHECK (status IN ('invited', 'quoted', 'declined'))
);

-- One invite per supplier per round.
CREATE UNIQUE INDEX rfq_invite_rfq_supplier_idx ON rfq_invite (rfq_id, supplier_id);
CREATE INDEX rfq_invite_tenant_rfq_idx ON rfq_invite (tenant_id, rfq_id);

CREATE TRIGGER rfq_invite_touch_updated_at
    BEFORE UPDATE ON rfq_invite
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('rfq_invite');

-- ---------------------------------------------------------------------------

CREATE TABLE rfq_quote (
    id             uuid          PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id      uuid          NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    rfq_id         uuid          NOT NULL REFERENCES rfq (id) ON DELETE CASCADE,
    supplier_id    text          NOT NULL,
    name           text          NOT NULL,
    declined       boolean       NOT NULL DEFAULT false,
    quoted_unit    numeric(14,4),
    quoted_landed  numeric(14,4),
    currency       text          NOT NULL DEFAULT 'USD',
    lead_days      integer,
    valid_until    date,
    payment_terms  text,
    note           text,
    vs_expected_pct numeric(6,2),
    received_at    timestamptz   NOT NULL DEFAULT now(),
    -- Null when the reply was simulated rather than typed in by a buyer.
    entered_by     uuid          REFERENCES users (id) ON DELETE SET NULL,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    updated_at     timestamptz   NOT NULL DEFAULT now(),
    version        bigint        NOT NULL DEFAULT 0,

    CONSTRAINT rfq_quote_declined_ck CHECK (
        (declined AND quoted_unit IS NULL AND quoted_landed IS NULL)
        OR (NOT declined AND quoted_landed IS NOT NULL)
    )
);

-- One reply per supplier per round.
CREATE UNIQUE INDEX rfq_quote_rfq_supplier_idx ON rfq_quote (rfq_id, supplier_id);
CREATE INDEX rfq_quote_tenant_rfq_idx ON rfq_quote (tenant_id, rfq_id);

CREATE TRIGGER rfq_quote_touch_updated_at
    BEFORE UPDATE ON rfq_quote
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('rfq_quote');

COMMENT ON TABLE rfq IS 'One request-for-quotation round: an order, its invited suppliers, and its award.';
COMMENT ON TABLE rfq_invite IS 'One supplier invited into a round, with the panel figures the invite was drawn against.';
COMMENT ON TABLE rfq_quote IS 'One invite''s reply - declined, or a quote against the round''s item and quantity.';
