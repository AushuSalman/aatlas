-- ============================================================================
--  V5  Role policy, tenant settings, password reset
--
--  Three things signup and sign-in need that V4 left for later:
--
--    role_policy            the eight personas as data, with per-tenant overrides
--    tenant_settings        country and trading currency, one row per tenant
--    password_reset_tokens  the "forgot my password" flow
--
--  Also relaxes tenants_currency_ck: a tenant may now trade in any of the ten
--  currencies in seed/currencies.json, not only the one its country implies.
-- ============================================================================

-- ---------------------------------------------------------------------------
--  role_policy
--
--  What a seat may do, as rows rather than as an enum. A row with tenant_id
--  NULL is the platform default; a row with a tenant_id overrides that default
--  for one company. The reader merges the two, so a tenant that changes only
--  the buyer's approval limit still gets every other persona from the defaults.
--
--  Seeded from seed/personas.json (generated from the frontend's PERSONAS in
--  src/lib/platform/session.ts). RolesIT pins the rows to the seed file.
--
--  approver_role is the seat that signs off above approve_limit, stored as the
--  role key and rendered as that seat's title ("Head of purchasing") at read
--  time, so a renamed persona cannot leave a stale name in the approver field.
-- ---------------------------------------------------------------------------
CREATE TABLE role_policy (
    id            uuid          PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id     uuid          REFERENCES tenants (id) ON DELETE CASCADE,
    role          text          NOT NULL,
    title         text          NOT NULL,
    side          text          NOT NULL,
    level         text          NOT NULL,
    modules       text[]        NOT NULL,
    bulk          boolean       NOT NULL DEFAULT false,
    guardrails    boolean       NOT NULL DEFAULT false,
    approve_limit numeric(14,4),
    approver_role text,
    blurb         text          NOT NULL DEFAULT '',
    created_at    timestamptz   NOT NULL DEFAULT now(),
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    version       bigint        NOT NULL DEFAULT 0,

    CONSTRAINT role_policy_role_ck CHECK (role IN (
        'sales-rep', 'seller', 'sales-head',
        'purchase-manager', 'buyer', 'purchase-head',
        'finance', 'both')),
    CONSTRAINT role_policy_side_ck  CHECK (side IN ('sell', 'buy', 'both', 'none')),
    CONSTRAINT role_policy_level_ck CHECK (level IN ('rep', 'manager', 'head', 'exec')),
    CONSTRAINT role_policy_limit_ck CHECK (approve_limit IS NULL OR approve_limit > 0),
    CONSTRAINT role_policy_approver_ck CHECK (approver_role IS NULL OR approver_role IN (
        'sales-rep', 'seller', 'sales-head',
        'purchase-manager', 'buyer', 'purchase-head',
        'finance', 'both'))
);

-- One default per role, one override per tenant per role. Two partial indexes
-- because NULL never equals NULL in a plain unique constraint.
CREATE UNIQUE INDEX role_policy_default_uk ON role_policy (role) WHERE tenant_id IS NULL;
CREATE UNIQUE INDEX role_policy_tenant_uk  ON role_policy (tenant_id, role) WHERE tenant_id IS NOT NULL;

CREATE TRIGGER role_policy_touch_updated_at
    BEFORE UPDATE ON role_policy
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

COMMENT ON TABLE  role_policy IS 'What each seat may open, bulk, change and approve. tenant_id NULL = platform default; a tenant row overrides it.';
COMMENT ON COLUMN role_policy.approver_role IS 'Seat that signs off above approve_limit; rendered as that seat title at read time.';

INSERT INTO role_policy (tenant_id, role, title, side, level, modules, bulk, guardrails, approve_limit, approver_role, blurb) VALUES
    (NULL, 'sales-rep',        'Sales rep',           'sell', 'rep',     ARRAY['sell','products','history'],                                      false, false, NULL,        NULL,            'Quotes one customer at a time. Sees the price to charge and why.'),
    (NULL, 'seller',           'Sales manager',       'sell', 'manager', ARRAY['sell','insights','stores','products','history'],                  true,  false, NULL,        NULL,            'Prices, bulk repricing and the branch view.'),
    (NULL, 'sales-head',       'Head of sales',       'sell', 'head',    ARRAY['sell','insights','stores','products','history'],                  true,  true,  NULL,        NULL,            'Everything sales, plus the guardrails the team prices inside.'),
    (NULL, 'purchase-manager', 'Purchase manager',    'buy',  'manager', ARRAY['buy','suppliers','insights','products','history'],                false, false, 50000.0000,  'purchase-head', 'Sources one order at a time, runs RFQs; larger orders go up for approval.'),
    (NULL, 'buyer',            'Category buyer',      'buy',  'manager', ARRAY['buy','suppliers','insights','stores','products','history'],       true,  false, 150000.0000, 'purchase-head', 'Owns a category: orders, baskets, suppliers; the largest orders go up for approval.'),
    (NULL, 'purchase-head',    'Head of purchasing',  'buy',  'head',    ARRAY['buy','suppliers','insights','stores','products','history'],       true,  true,  NULL,        NULL,            'Everything procurement, supplier panel, approvals without limit.'),
    (NULL, 'finance',          'Finance',             'none', 'exec',    ARRAY['suppliers','insights','stores','products','history'],             false, true,  NULL,        NULL,            'Reads the numbers and the decisions; sets the margin floor. Does not price or buy.'),
    (NULL, 'both',             'Commercial director', 'both', 'exec',    ARRAY['sell','buy','suppliers','insights','stores','products','history'], true,  true,  NULL,        NULL,            'The full platform, both sides.');

-- Row-level security with a twist: the defaults (tenant_id NULL) are readable
-- by every tenant, overrides only by their own. The standard policy from
-- app.enable_tenant_rls() would hide the defaults, so this one is spelled out.
-- WITH CHECK also admits NULL so a later data migration can add a default row
-- without a tenant bound to the connection; the application never inserts one.
ALTER TABLE role_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE role_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON role_policy
    USING      (tenant_id IS NULL OR tenant_id = app.current_tenant())
    WITH CHECK (tenant_id IS NULL OR tenant_id = app.current_tenant());

-- ---------------------------------------------------------------------------
--  tenant_settings
--
--  The source of truth for where a tenant trades and in what. tenants.country
--  and tenants.trading_currency stay as a denormalised copy that the tenant
--  module rewrites in the same transaction as this row, because signup's
--  session and every report already read them from there; the copy is never
--  written by anything else.
--
--  Keyed by tenant_id rather than an id of its own: there is exactly one row
--  per tenant and a surrogate key would only be something to join through.
--
--  Not under RLS, deliberately: signup inserts this row before any tenant is
--  bound to the connection, the same pre-tenant path V4 exempts tenants and
--  users from.
-- ---------------------------------------------------------------------------
CREATE TABLE tenant_settings (
    tenant_id        uuid        PRIMARY KEY REFERENCES tenants (id) ON DELETE CASCADE,
    country_code     varchar(2)  NOT NULL,
    trading_currency varchar(3)  NOT NULL,
    updated_by       uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    version          bigint      NOT NULL DEFAULT 0,

    CONSTRAINT tenant_settings_country_ck  CHECK (country_code IN ('US', 'UK')),
    CONSTRAINT tenant_settings_currency_ck CHECK (trading_currency IN (
        'USD', 'GBP', 'EUR', 'CAD', 'AUD', 'INR', 'MXN', 'CNY', 'VND', 'JPY'))
);

CREATE TRIGGER tenant_settings_touch_updated_at
    BEFORE UPDATE ON tenant_settings
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

COMMENT ON TABLE  tenant_settings IS 'Country and trading currency per tenant. Source of truth; tenants.country / trading_currency mirror it.';
COMMENT ON COLUMN tenant_settings.updated_by IS 'NULL when the row still holds what signup chose.';

-- Every tenant that exists already gets its row from the copy on tenants.
INSERT INTO tenant_settings (tenant_id, country_code, trading_currency, created_at, updated_at)
SELECT id, country, trading_currency, created_at, updated_at FROM tenants;

-- The copy on tenants may now hold any supported currency, not only USD/GBP.
ALTER TABLE tenants DROP CONSTRAINT tenants_currency_ck;
ALTER TABLE tenants ADD CONSTRAINT tenants_currency_ck CHECK (trading_currency IN (
    'USD', 'GBP', 'EUR', 'CAD', 'AUD', 'INR', 'MXN', 'CNY', 'VND', 'JPY'));

-- ---------------------------------------------------------------------------
--  password_reset_tokens
--
--  Same shape of thinking as refresh_tokens: only the SHA-256 of the token is
--  stored, a token is single-use, and rows survive being spent so a second
--  presentation is recognised as "already used" rather than "never issued".
--
--  Not under RLS: reset runs before sign-in, with no tenant on the connection.
-- ---------------------------------------------------------------------------
CREATE TABLE password_reset_tokens (
    id          uuid        PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id   uuid        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_id     uuid        NOT NULL REFERENCES users (id)   ON DELETE CASCADE,
    token_hash  bytea       NOT NULL,
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    client_ip   text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     bigint      NOT NULL DEFAULT 0,

    CONSTRAINT password_reset_tokens_hash_ck CHECK (length(token_hash) = 32)
);

CREATE UNIQUE INDEX password_reset_tokens_hash_uk ON password_reset_tokens (token_hash);
CREATE INDEX password_reset_tokens_user_idx ON password_reset_tokens (tenant_id, user_id, expires_at DESC);

CREATE TRIGGER password_reset_tokens_touch_updated_at
    BEFORE UPDATE ON password_reset_tokens
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

COMMENT ON TABLE password_reset_tokens IS 'Single-use password reset tokens. Only the SHA-256 is stored; one hour to live.';
