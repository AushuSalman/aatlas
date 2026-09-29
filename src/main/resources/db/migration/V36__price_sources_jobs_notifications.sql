-- ============================================================================
--  V36 - price sources per tenant, background competitor-price jobs, notifications
--
--  competition_settings        which live price sources a tenant has switched on. The
--                              keys are the platform's (server environment); a tenant only
--                              chooses among the configured ones. configured_at null = the
--                              tenant has not chosen yet (the post-import prompt asks).
--  competitor_refresh_jobs     one background run of "fetch competitor prices" over a list
--                              of items: the setup run over the whole catalogue, or the
--                              automatic run over the products a later import brought in.
--  notifications               what the bell shows: one row per event, for the whole tenant.
--  notification_reads          who has read which notification (read state is per person).
--
--  Conventions from V1: uuid v7 keys, tenant_id first, updated_at by trigger,
--  app.enable_tenant_rls() for isolation.
-- ============================================================================

CREATE TABLE competition_settings (
    tenant_id        uuid          PRIMARY KEY REFERENCES tenants (id) ON DELETE CASCADE,
    enabled_sources  text[]        NOT NULL DEFAULT '{}',
    configured_at    timestamptz,
    updated_by       uuid,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    version          bigint        NOT NULL DEFAULT 0
);

CREATE TRIGGER competition_settings_touch_updated_at
    BEFORE UPDATE ON competition_settings
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('competition_settings');

COMMENT ON TABLE competition_settings IS
    'Which live competitor-price sources a tenant uses (serpapi, ebay, rainforest, oxylabs, site). Keys live on the server.';

CREATE TABLE competitor_refresh_jobs (
    id              uuid          PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id       uuid          NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    trigger         text          NOT NULL,
    status          text          NOT NULL DEFAULT 'queued',
    sources         text[]        NOT NULL,
    items           text[]        NOT NULL,
    total           integer       NOT NULL,
    done            integer       NOT NULL DEFAULT 0,
    priced          integer       NOT NULL DEFAULT 0,
    observations    integer       NOT NULL DEFAULT 0,
    failed          integer       NOT NULL DEFAULT 0,
    error           text,
    started_at      timestamptz,
    finished_at     timestamptz,
    created_by      uuid,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    version         bigint        NOT NULL DEFAULT 0,

    CONSTRAINT competitor_refresh_jobs_trigger_ck CHECK (trigger IN ('setup', 'import', 'manual')),
    CONSTRAINT competitor_refresh_jobs_status_ck  CHECK (status IN ('queued', 'running', 'done', 'failed')),
    CONSTRAINT competitor_refresh_jobs_counts_ck  CHECK (done >= 0 AND done <= total AND priced <= done)
);

CREATE INDEX competitor_refresh_jobs_tenant_idx ON competitor_refresh_jobs (tenant_id, created_at DESC);

CREATE TRIGGER competitor_refresh_jobs_touch_updated_at
    BEFORE UPDATE ON competitor_refresh_jobs
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('competitor_refresh_jobs');

COMMENT ON TABLE competitor_refresh_jobs IS
    'Background runs fetching competitor prices for a list of items, with progress. The bell is told when one ends.';

CREATE TABLE notifications (
    id          uuid          PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id   uuid          NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    kind        text          NOT NULL,
    title       text          NOT NULL,
    body        text,
    link        text,
    created_at  timestamptz   NOT NULL DEFAULT now(),

    CONSTRAINT notifications_title_ck CHECK (length(btrim(title)) BETWEEN 1 AND 200)
);

CREATE INDEX notifications_tenant_idx ON notifications (tenant_id, created_at DESC);

SELECT app.enable_tenant_rls('notifications');

COMMENT ON TABLE notifications IS 'What the bell shows: one row per event, visible to everyone in the tenant.';

CREATE TABLE notification_reads (
    tenant_id        uuid          NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    notification_id  uuid          NOT NULL REFERENCES notifications (id) ON DELETE CASCADE,
    user_id          uuid          NOT NULL,
    read_at          timestamptz   NOT NULL DEFAULT now(),

    PRIMARY KEY (notification_id, user_id)
);

CREATE INDEX notification_reads_tenant_user_idx ON notification_reads (tenant_id, user_id);

SELECT app.enable_tenant_rls('notification_reads');
