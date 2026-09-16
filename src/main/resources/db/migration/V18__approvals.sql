-- ============================================================================
--  V18  Approvals
--
--  One table: what the blueprint's `approval_request` names, no more - id,
--  decision_id, requested_by, approver_role, amount, status, decided_by,
--  decided_at, note. `decision_id` always points at a `decision` row already
--  written as 'pending' by the module that raised the request (see
--  `decisions.DecisionRecorder#recordPending`); this table only ever decides
--  whether that decision proceeds, never what it commits.
--
--  Conventions from V1 apply: uuid v7 primary key, tenant_id first in the
--  index, created_at / updated_at / version, updated_at maintained by
--  trigger, row-level security enabled.
-- ============================================================================

CREATE TABLE approval_request (
    id            uuid          PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id     uuid          NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    decision_id   uuid          NOT NULL REFERENCES decision (id) ON DELETE CASCADE,
    requested_by  uuid          NOT NULL REFERENCES users (id) ON DELETE SET NULL,
    approver_role text          NOT NULL,
    amount        numeric(14,4) NOT NULL,
    status        text          NOT NULL DEFAULT 'pending',
    decided_by    uuid          REFERENCES users (id) ON DELETE SET NULL,
    decided_at    timestamptz,
    note          text,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    version       bigint        NOT NULL DEFAULT 0,

    CONSTRAINT approval_request_status_ck CHECK (status IN ('pending', 'approved', 'rejected')),
    CONSTRAINT approval_request_amount_ck CHECK (amount >= 0),
    -- A decided request always carries who decided it and when; a pending one carries neither.
    CONSTRAINT approval_request_decision_ck CHECK (
        (status = 'pending' AND decided_by IS NULL AND decided_at IS NULL)
        OR (status <> 'pending' AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
    )
);

-- One open request per decision - a second raise on the same pending decision is a bug
-- upstream, not a second row here.
CREATE UNIQUE INDEX approval_request_one_per_decision_idx ON approval_request (decision_id);

-- What the approver's inbox reads: this tenant's outstanding requests for one role.
CREATE INDEX approval_request_pending_by_role_idx
    ON approval_request (tenant_id, approver_role, created_at DESC)
    WHERE status = 'pending';

-- "Mine": everything a user has ever raised, newest first.
CREATE INDEX approval_request_requested_by_idx ON approval_request (tenant_id, requested_by, created_at DESC);

CREATE TRIGGER approval_request_touch_updated_at
    BEFORE UPDATE ON approval_request
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('approval_request');

COMMENT ON TABLE approval_request IS
    'A decision raised over a seat''s approval limit, waiting on (or decided by) approver_role.';
COMMENT ON COLUMN approval_request.decision_id IS
    'The decision this request would commit; its own status mirrors this row''s (pending/approved/rejected).';
