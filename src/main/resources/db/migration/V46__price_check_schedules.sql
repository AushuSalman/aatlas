-- ============================================================================
--  V46 - price-check schedules
--
--  Competitor prices are fetched only when a schedule says so - nothing runs on its own any
--  more (the fixed daily run and the silent run after each product import are gone). A
--  schedule is a named profile: which products (all, some categories, or picked items),
--  which sources, when it starts, and whether it runs once or repeats every N hours, days,
--  weeks or months, in the tenant's time zone. Each run is a competitor_refresh_jobs row
--  pointing back at its schedule.
-- ============================================================================

CREATE TABLE price_check_schedules (
    id            uuid          PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id     uuid          NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name          text          NOT NULL,
    scope         text          NOT NULL,
    categories    text[]        NOT NULL DEFAULT '{}',
    items         text[]        NOT NULL DEFAULT '{}',
    sources       text[]        NOT NULL,
    starts_at     timestamptz   NOT NULL,
    repeat_every  integer,
    repeat_unit   text,
    time_zone     text          NOT NULL DEFAULT 'UTC',
    active        boolean       NOT NULL DEFAULT true,
    next_run_at   timestamptz,
    last_run_at   timestamptz,
    created_by    uuid,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    version       bigint        NOT NULL DEFAULT 0,

    CONSTRAINT price_check_schedules_name_ck   CHECK (length(btrim(name)) BETWEEN 1 AND 80),
    CONSTRAINT price_check_schedules_scope_ck  CHECK (scope IN ('all', 'categories', 'items')),
    CONSTRAINT price_check_schedules_unit_ck   CHECK (repeat_unit IN ('hours', 'days', 'weeks', 'months')),
    CONSTRAINT price_check_schedules_repeat_ck CHECK ((repeat_every IS NULL) = (repeat_unit IS NULL)
                                                     AND (repeat_every IS NULL OR repeat_every BETWEEN 1 AND 365))
);

CREATE UNIQUE INDEX price_check_schedules_name_uq ON price_check_schedules (tenant_id, lower(name));
CREATE INDEX price_check_schedules_due_idx ON price_check_schedules (tenant_id, next_run_at)
    WHERE active AND next_run_at IS NOT NULL;

CREATE TRIGGER price_check_schedules_touch_updated_at
    BEFORE UPDATE ON price_check_schedules
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('price_check_schedules');

COMMENT ON TABLE price_check_schedules IS
    'Named competitor-price check profiles: products (all / categories / items), sources, start, once or every N hours/days/weeks/months.';
COMMENT ON COLUMN price_check_schedules.next_run_at IS
    'When it runs next; null once a one-time schedule has run.';

ALTER TABLE competitor_refresh_jobs
    ADD COLUMN schedule_id uuid REFERENCES price_check_schedules (id) ON DELETE SET NULL;

CREATE INDEX competitor_refresh_jobs_schedule_idx ON competitor_refresh_jobs (tenant_id, schedule_id, created_at DESC)
    WHERE schedule_id IS NOT NULL;

ALTER TABLE competitor_refresh_jobs DROP CONSTRAINT competitor_refresh_jobs_trigger_ck;
ALTER TABLE competitor_refresh_jobs ADD CONSTRAINT competitor_refresh_jobs_trigger_ck
    CHECK (trigger IN ('setup', 'import', 'manual', 'daily', 'schedule'));

-- The fixed daily run is replaced by schedules.
ALTER TABLE competition_settings DROP COLUMN daily_refresh;
