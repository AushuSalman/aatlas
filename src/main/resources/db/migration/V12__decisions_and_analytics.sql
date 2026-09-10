-- ============================================================================
--  V12  Decisions, deals, quotes and the procurement ledger
--
--  Two modules share this migration number: `decisions` (what a user actually
--  did - the applied recommendations, the deals that resulted, the quote
--  breakdown behind a sell decision) and `analytics` (the procurement ledger
--  the Buying insights dashboard reduces at read time).
--
--  Conventions from V1 apply: uuid v7 primary keys, tenant_id first in every
--  composite index, created_at / updated_at / version on every row, updated_at
--  maintained by trigger, row-level security via app.enable_tenant_rls.
-- ============================================================================

-- ---------------------------------------------------------------------------
--  decision
--
--  One row per recommendation a user acted on: a sell price applied, a buy
--  line moved to a new supplier, a bulk plan applied across many lines. Ported
--  from the frontend's `Decision` (src/lib/intel/decisions.ts), with one
--  addition the browser-only prototype had no need for: `status`, so an
--  approval workflow (the `approvals` module) has somewhere to move a decision
--  from `pending` to `approved`/`rejected` before `applied` is used to gate a
--  purchase order (see purchase_order.decision_id below).
-- ---------------------------------------------------------------------------
CREATE TABLE decision (
    id               uuid          PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id        uuid          NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_id          uuid          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind             text          NOT NULL,
    title            text          NOT NULL,
    item_number      text,
    scope            text          NOT NULL DEFAULT '',
    recommended      numeric(14,4) NOT NULL DEFAULT 0,
    applied          numeric(14,4) NOT NULL DEFAULT 0,
    expected_impact  numeric(14,4) NOT NULL DEFAULT 0,
    impact_label     text          NOT NULL DEFAULT '',
    detail           text          NOT NULL DEFAULT '',
    count            integer,
    status           text          NOT NULL DEFAULT 'applied',
    created_at       timestamptz   NOT NULL DEFAULT now(),
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    version          bigint        NOT NULL DEFAULT 0,

    CONSTRAINT decision_kind_ck   CHECK (kind IN ('sell', 'buy', 'bulk-sell', 'bulk-buy')),
    CONSTRAINT decision_status_ck CHECK (status IN ('applied', 'pending', 'approved', 'rejected')),
    CONSTRAINT decision_title_ck  CHECK (length(btrim(title)) BETWEEN 1 AND 300)
);

-- The recent-decisions feed: newest first, per tenant.
CREATE INDEX decision_tenant_created_idx ON decision (tenant_id, created_at DESC);
-- DELETE /decisions/mine.
CREATE INDEX decision_tenant_user_idx ON decision (tenant_id, user_id, created_at DESC);

CREATE TRIGGER decision_touch_updated_at
    BEFORE UPDATE ON decision
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('decision');

COMMENT ON TABLE  decision IS 'A recommendation a user acted on. Ported from the frontend Decision (intel/decisions.ts).';
COMMENT ON COLUMN decision.item_number IS 'Frontend item number string (HRD118902), not a foreign key - see catalog''s own keying.';
COMMENT ON COLUMN decision.status IS 'applied = the browser-only prototype''s only state; pending/approved/rejected exist for the approvals workflow.';

-- ---------------------------------------------------------------------------
--  deal
--
--  One row per closed sale or purchase, seeded (the 181 historical deals in
--  seed/deals.json, recorded = false) or written live when a decision is
--  recorded (recorded = true). Ported from the frontend's `DealRow`
--  (src/lib/platform/types.ts): this is what `buildImpact`/`getHistory`
--  reduce, so a seeded row and a live one are read by exactly the same code.
--
--  Keyed by the frontend's own id strings (item_number, counterparty text),
--  matching how `catalog`'s tables key products and stores - see the seed
--  listener for why a synthetic uuid foreign key is not worth it here.
-- ---------------------------------------------------------------------------
CREATE TABLE deal (
    id               uuid          PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id        uuid          NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    deal_key         text          NOT NULL,
    decision_id      uuid          REFERENCES decision (id) ON DELETE SET NULL,
    side             text          NOT NULL,
    item_number      text          NOT NULL,
    description      text          NOT NULL DEFAULT '',
    counterparty     text          NOT NULL DEFAULT '',
    qty              integer       NOT NULL,
    cost             numeric(14,4) NOT NULL DEFAULT 0,
    baseline_price    numeric(14,4) NOT NULL DEFAULT 0,
    suggested_price  numeric(14,4) NOT NULL DEFAULT 0,
    actual_price     numeric(14,4) NOT NULL DEFAULT 0,
    followed         boolean       NOT NULL,
    gain             numeric(14,4) NOT NULL DEFAULT 0,
    lost             numeric(14,4) NOT NULL DEFAULT 0,
    recorded         boolean       NOT NULL DEFAULT false,
    customer         text,
    recorded_at      timestamptz,
    below_floor      boolean,
    destination_id   text,
    deal_date        date          NOT NULL,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    version          bigint        NOT NULL DEFAULT 0,

    CONSTRAINT deal_side_ck CHECK (side IN ('sell', 'buy')),
    CONSTRAINT deal_qty_ck  CHECK (qty >= 0)
);

-- History/impact reduction: newest first, per tenant.
CREATE UNIQUE INDEX deal_tenant_key_uk ON deal (tenant_id, deal_key);
CREATE INDEX deal_tenant_date_idx ON deal (tenant_id, deal_date DESC);
CREATE INDEX deal_tenant_side_idx ON deal (tenant_id, side);
CREATE INDEX deal_tenant_item_idx ON deal (tenant_id, item_number);
CREATE INDEX deal_decision_idx ON deal (decision_id);

CREATE TRIGGER deal_touch_updated_at
    BEFORE UPDATE ON deal
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('deal');

COMMENT ON TABLE  deal IS 'A closed sale or purchase: seeded history (recorded = false) plus live-recorded deals. Ported from the frontend DealRow.';
COMMENT ON COLUMN deal.deal_key IS 'The frontend''s own DealRow.id string (d-s-99 for a seeded row, rec-... /recb-... for one recorded here) - the wire id. Not the uuid primary key.';
COMMENT ON COLUMN deal.recorded IS 'False for the 181 seeded historical rows; true for a deal this workspace actually recorded.';
COMMENT ON COLUMN deal.gain IS 'Followed: profit vs. baseline. Not followed: 0. Can be negative - see DealRow.';
COMMENT ON COLUMN deal.lost IS 'Not followed: profit given up vs. the suggestion. Followed: 0.';

-- ---------------------------------------------------------------------------
--  quote
--
--  The customer-quote breakdown behind a sell decision - ported from the
--  frontend's `DealQuote` (src/lib/platform/deal.ts, `quoteForDeal`): volume
--  break, customer agreement, book price vs. deal-adjusted price, and whether
--  the margin floor clamped the stacked discounts. One per decision, only for
--  sell-side decisions carrying deal context.
-- ---------------------------------------------------------------------------
CREATE TABLE quote (
    id                       uuid          PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id                uuid          NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    decision_id              uuid          NOT NULL REFERENCES decision (id) ON DELETE CASCADE,
    qty                      integer       NOT NULL,
    volume_break_pct         numeric(6,2)  NOT NULL DEFAULT 0,
    next_break_at            integer,
    next_break_pct           numeric(6,2),
    customer_discount_pct    numeric(6,2)  NOT NULL DEFAULT 0,
    book_optimal             numeric(14,4) NOT NULL DEFAULT 0,
    book_aggressive          numeric(14,4) NOT NULL DEFAULT 0,
    optimal                  numeric(14,4) NOT NULL DEFAULT 0,
    aggressive               numeric(14,4) NOT NULL DEFAULT 0,
    recommended              numeric(14,4) NOT NULL DEFAULT 0,
    recommended_tier         text          NOT NULL,
    clamped_by_floor         boolean       NOT NULL DEFAULT false,
    effective_discount_pct   numeric(6,2)  NOT NULL DEFAULT 0,
    created_at               timestamptz   NOT NULL DEFAULT now(),
    updated_at               timestamptz   NOT NULL DEFAULT now(),
    version                  bigint        NOT NULL DEFAULT 0,

    CONSTRAINT quote_tier_ck CHECK (recommended_tier IN ('optimal', 'aggressive'))
);

CREATE UNIQUE INDEX quote_decision_uk ON quote (decision_id);
CREATE INDEX quote_tenant_idx ON quote (tenant_id);

CREATE TRIGGER quote_touch_updated_at
    BEFORE UPDATE ON quote
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('quote');

COMMENT ON TABLE quote IS 'The customer-quote breakdown behind a sell decision. Ported from the frontend DealQuote (platform/deal.ts).';

-- ---------------------------------------------------------------------------
--  purchase_order
--
--  The procurement ledger `analytics` reduces at read time for the Buying
--  insights dashboard - one row per purchase order line, landed at the branch
--  that ordered it. Ported from the frontend's `PurchaseOrder`
--  (src/lib/platform/procurement.ts) and written in full (~800 rows spanning
--  26 months) when the sample data source connects, rather than regenerated
--  per request.
--
--  decision_id badges a line that was actually awarded through a recorded buy
--  decision (POST /buy/select or a buy-side apply), alongside the seeded
--  backdrop - the same "Approved on Buy" idea the frontend's RFQ award flow
--  and recordPurchase carry, reproduced here as a real link rather than a
--  merge of two in-memory arrays.
-- ---------------------------------------------------------------------------
CREATE TABLE purchase_order (
    id               uuid          PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id        uuid          NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    seq              integer       NOT NULL,
    po_number        text          NOT NULL,
    order_date       date          NOT NULL,
    supplier_id      text          NOT NULL,
    supplier_name    text          NOT NULL,
    country          text          NOT NULL,
    item_number      text          NOT NULL,
    description      text          NOT NULL DEFAULT '',
    category         text          NOT NULL,
    branch_id        text          NOT NULL,
    branch_name      text          NOT NULL,
    region_key       text          NOT NULL,
    region_label     text          NOT NULL,
    qty              integer       NOT NULL,

    ex_works         numeric(14,4) NOT NULL,
    freight          numeric(14,4) NOT NULL,
    duty             numeric(14,4) NOT NULL,
    landed           numeric(14,4) NOT NULL,
    baseline         numeric(14,4) NOT NULL,
    target           numeric(14,4) NOT NULL,
    followed         boolean       NOT NULL,

    spend            numeric(14,4) NOT NULL,
    baseline_spend   numeric(14,4) NOT NULL,
    saved            numeric(14,4) NOT NULL,
    leaked           numeric(14,4) NOT NULL,

    status           text          NOT NULL,
    promised_days    integer       NOT NULL,
    actual_days      integer       NOT NULL,
    days_late        integer       NOT NULL,
    on_time          boolean       NOT NULL,
    promised_date    date          NOT NULL,
    received_date    date,

    decision_id      uuid          REFERENCES decision (id) ON DELETE SET NULL,

    created_at       timestamptz   NOT NULL DEFAULT now(),
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    version          bigint        NOT NULL DEFAULT 0,

    CONSTRAINT purchase_order_status_ck CHECK (status IN ('received', 'in-transit', 'open')),
    CONSTRAINT purchase_order_qty_ck    CHECK (qty >= 0)
);

-- The dashboard's main scan: a date-windowed reduction per tenant. seq ASC after
-- order_date DESC reproduces the exact stable sort buildLedger() finishes with
-- (newest first, ties broken by build order - earlier-built rows first among an
-- equal date), which is what the golden-file test pins the first 50 ledger rows
-- against.
CREATE INDEX purchase_order_tenant_date_idx ON purchase_order (tenant_id, order_date DESC, seq ASC);
CREATE UNIQUE INDEX purchase_order_tenant_po_uk ON purchase_order (tenant_id, po_number);
CREATE INDEX purchase_order_tenant_branch_idx ON purchase_order (tenant_id, branch_id);
CREATE INDEX purchase_order_tenant_category_idx ON purchase_order (tenant_id, category);
CREATE INDEX purchase_order_tenant_supplier_idx ON purchase_order (tenant_id, supplier_id);
CREATE INDEX purchase_order_tenant_received_idx ON purchase_order (tenant_id, received_date);
CREATE INDEX purchase_order_decision_idx ON purchase_order (decision_id);

CREATE TRIGGER purchase_order_touch_updated_at
    BEFORE UPDATE ON purchase_order
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('purchase_order');

COMMENT ON TABLE  purchase_order IS 'The procurement ledger. Ported from the frontend PurchaseOrder (platform/procurement.ts); ~800 rows over 26 months, written once when the sample data connects.';
COMMENT ON COLUMN purchase_order.spend IS 'landed * qty. What "spend" means everywhere on the Buying insights dashboard.';
COMMENT ON COLUMN purchase_order.decision_id IS 'Set when this line was awarded through a recorded buy decision, badging it alongside the seeded backdrop.';
COMMENT ON COLUMN purchase_order.seq IS 'Build order (newest month/order-index first before the final date sort). Not business data - lets a read reproduce buildLedger()''s exact stable sort.';
