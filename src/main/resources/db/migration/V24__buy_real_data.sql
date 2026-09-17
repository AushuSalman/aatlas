-- ============================================================================
--  V24  Buy + RFQ, real data
--
--  Two small additive changes for the real-data/buy worktree.
--
--  1. rfq_quote.simulated: RfqEngine.simulate is the one place left that may
--     still call Seeded (spec-A S2 "rfq"), and only for the sample tenant's
--     demo replies. The wire already carried entered_by = null as an implicit
--     signal, but the frontend needs an explicit boolean to badge "Simulated
--     (demo)" without guessing from nullability alone.
--
--  2. buy_decisions.supplier_id / decision_id: POST /buy/select now records
--     which supplier was actually chosen (previously conflated with
--     option_key - see BuySelectRequest), and the real decisions.Decision id
--     so a pending award can be resolved by ApprovalGranted/ApprovalRejected
--     the same way rfq.RfqEntity already tracks it (V19).
--
--  Conventions from V1 apply.
-- ============================================================================

ALTER TABLE rfq_quote
    ADD COLUMN simulated boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN rfq_quote.simulated IS
    'True when RfqEngine.simulate generated this reply (sample tenant only). Never a real supplier reply.';

ALTER TABLE buy_decisions
    ADD COLUMN supplier_id text,
    ADD COLUMN decision_id uuid,
    ADD COLUMN qty integer;

CREATE INDEX buy_decisions_tenant_decision_idx ON buy_decisions (tenant_id, decision_id);

COMMENT ON COLUMN buy_decisions.supplier_id IS
    'The supplier_key actually chosen (BuySelectRequest.supplierId). Null for requests made before this column existed.';
COMMENT ON COLUMN buy_decisions.decision_id IS
    'The real decisions.Decision this row mirrors, so a pending award can be resolved by the approvals outcome listener.';
COMMENT ON COLUMN buy_decisions.qty IS
    'The order quantity, carried so a granted approval can mirror the purchase with the quantity actually requested.';
