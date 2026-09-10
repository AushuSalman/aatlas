-- ============================================================================
--  V6  Pricing guardrails and FX rates
--
--    pricing_guardrails          the four limits every recommendation respects
--    pricing_guardrail_history   who changed them, to what, when
--    fx_rate                     how the ten trading currencies relate to USD
-- ============================================================================

-- ---------------------------------------------------------------------------
--  pricing_guardrails
--
--  One row per tenant, and only once someone has saved: a tenant with no row
--  is on the platform defaults from seed/guardrails.json (25 / 15 / 8 / 10),
--  which is what the frontend's DEFAULT_GUARDRAILS shows before anyone edits.
--
--  Ranges match GUARDRAIL_FIELDS in the frontend's src/lib/intel/guardrails.ts
--  so the form and the API refuse the same values. GuardrailLimitsTest pins them.
-- ---------------------------------------------------------------------------
CREATE TABLE pricing_guardrails (
    tenant_id                uuid         PRIMARY KEY REFERENCES tenants (id) ON DELETE CASCADE,
    min_margin_pct           numeric(5,2) NOT NULL,
    max_discount_pct         numeric(5,2) NOT NULL,
    max_speed_premium_pct    numeric(5,2) NOT NULL,
    max_market_deviation_pct numeric(5,2) NOT NULL,
    updated_by               uuid         REFERENCES users (id) ON DELETE SET NULL,
    created_at               timestamptz  NOT NULL DEFAULT now(),
    updated_at               timestamptz  NOT NULL DEFAULT now(),
    version                  bigint       NOT NULL DEFAULT 0,

    CONSTRAINT pricing_guardrails_margin_ck    CHECK (min_margin_pct           BETWEEN 5 AND 60),
    CONSTRAINT pricing_guardrails_discount_ck  CHECK (max_discount_pct         BETWEEN 0 AND 40),
    CONSTRAINT pricing_guardrails_speed_ck     CHECK (max_speed_premium_pct    BETWEEN 0 AND 25),
    CONSTRAINT pricing_guardrails_deviation_ck CHECK (max_market_deviation_pct BETWEEN 0 AND 30)
);

CREATE TRIGGER pricing_guardrails_touch_updated_at
    BEFORE UPDATE ON pricing_guardrails
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('pricing_guardrails');

COMMENT ON TABLE pricing_guardrails IS 'The four pricing limits per tenant. No row = platform defaults from seed/guardrails.json.';

-- ---------------------------------------------------------------------------
--  pricing_guardrail_history
--
--  Append-only. The values are a jsonb snapshot because the history screen
--  renders them verbatim and nothing filters on a single limit; who and when
--  are real columns because the list is sorted and paged on them.
-- ---------------------------------------------------------------------------
CREATE TABLE pricing_guardrail_history (
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

    CONSTRAINT pricing_guardrail_history_action_ck CHECK (action IN ('set', 'reset'))
);

-- Newest first, keyset-paged on the v7 id, which is time-ordered.
CREATE INDEX pricing_guardrail_history_tenant_idx ON pricing_guardrail_history (tenant_id, id DESC);

CREATE TRIGGER pricing_guardrail_history_touch_updated_at
    BEFORE UPDATE ON pricing_guardrail_history
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('pricing_guardrail_history');

COMMENT ON TABLE pricing_guardrail_history IS 'Every save or reset of a tenant guardrails row, with the values as saved.';

-- ---------------------------------------------------------------------------
--  fx_rate
--
--  Reference data, shared by every tenant, so no tenant_id and no RLS. One row
--  per (base, quote, as_of); the reader takes the newest as_of per pair, which
--  is what lets a nightly job insert a fresh day without touching these rows.
--
--  Seeded from seed/currencies.json: the fixed demo rates as of 2026-09-01.
--  rate is "units of quote per one unit of base", i.e. the seed's perUsd.
-- ---------------------------------------------------------------------------
CREATE TABLE fx_rate (
    base       varchar(3)     NOT NULL,
    quote      varchar(3)     NOT NULL,
    as_of      date           NOT NULL,
    rate       numeric(18,8)  NOT NULL,
    source     text           NOT NULL DEFAULT 'seed',
    created_at timestamptz    NOT NULL DEFAULT now(),

    PRIMARY KEY (base, quote, as_of),
    CONSTRAINT fx_rate_positive_ck CHECK (rate > 0)
);

COMMENT ON TABLE  fx_rate IS 'Units of quote per one unit of base on as_of. Newest as_of per pair wins.';
COMMENT ON COLUMN fx_rate.source IS 'seed for the fixed demo rates; the nightly job writes its provider name.';

INSERT INTO fx_rate (base, quote, as_of, rate) VALUES
    ('USD', 'USD', DATE '2026-09-01', 1),
    ('USD', 'GBP', DATE '2026-09-01', 0.78),
    ('USD', 'EUR', DATE '2026-09-01', 0.91),
    ('USD', 'CAD', DATE '2026-09-01', 1.36),
    ('USD', 'AUD', DATE '2026-09-01', 1.52),
    ('USD', 'INR', DATE '2026-09-01', 84.2),
    ('USD', 'MXN', DATE '2026-09-01', 18.4),
    ('USD', 'CNY', DATE '2026-09-01', 7.18),
    ('USD', 'VND', DATE '2026-09-01', 25100),
    ('USD', 'JPY', DATE '2026-09-01', 148);
