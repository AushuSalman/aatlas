-- ============================================================================
--  V40 - what a decision actually did
--
--  A sell price applied is measured once enough time has passed (four weeks by default):
--  units and profit a week in the window after it, against the same window before, at the
--  same branch. One row per deal, keyed by its deal_key. The price change against the
--  volume change gives an observed price sensitivity for the item, which the pricing model
--  blends into its own estimate - so what happened, not only what was chosen, shapes the
--  next recommendation.
--
--  status: measured | insufficient (too few sales either side; retried while the decision
--  is recent, then kept as it is).
--  verdict: worked (profit a week up 2%+) | hurt (down 2%+) | neutral | insufficient.
-- ============================================================================

CREATE TABLE decision_outcomes (
    tenant_id           uuid           NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    deal_key            text           NOT NULL,
    side                text           NOT NULL,
    item_number         text           NOT NULL,
    store_code          text,
    decision_date       date           NOT NULL,
    window_days         integer        NOT NULL,
    status              text           NOT NULL,
    verdict             text           NOT NULL,
    units_before_week   numeric(14,4),
    units_after_week    numeric(14,4),
    price_before        numeric(14,4),
    price_after         numeric(14,4),
    profit_before_week  numeric(14,4),
    profit_after_week   numeric(14,4),
    price_change_pct    numeric(8,2),
    volume_change_pct   numeric(8,2),
    profit_change_pct   numeric(8,2),
    implied_elasticity  numeric(8,4),
    note                text,
    measured_at         timestamptz    NOT NULL DEFAULT now(),
    created_at          timestamptz    NOT NULL DEFAULT now(),
    updated_at          timestamptz    NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, deal_key),
    CONSTRAINT decision_outcomes_side_ck    CHECK (side IN ('sell', 'buy')),
    CONSTRAINT decision_outcomes_status_ck  CHECK (status IN ('measured', 'insufficient')),
    CONSTRAINT decision_outcomes_verdict_ck CHECK (verdict IN ('worked', 'hurt', 'neutral', 'insufficient'))
);

CREATE INDEX decision_outcomes_item_idx ON decision_outcomes (tenant_id, item_number, decision_date DESC);

CREATE TRIGGER decision_outcomes_touch_updated_at
    BEFORE UPDATE ON decision_outcomes
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('decision_outcomes');

COMMENT ON TABLE decision_outcomes IS
    'What each applied sell price did: units and profit a week after against before; its observed price sensitivity trains the pricing model.';
