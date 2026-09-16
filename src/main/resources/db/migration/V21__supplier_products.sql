-- ============================================================================
--  V21  Which suppliers carry which product
--
--  Closes the one relation the schema never had. Until now `suppliers` and
--  `products` were two islands: no foreign key, no join table, and no query
--  anywhere joining them. Every buy-side read therefore quoted the tenant's
--  whole panel on every item, so a copper coil and a ball valve came back with
--  the same eight suppliers. That is faithful to the prototype the engines were
--  ported from - `seed/suppliers.json` is a flat panel - but it means "who to
--  buy from" ranks suppliers without recording that they sell the thing.
--
--  Two changes here.
--
--  1. supplier_products: the relation itself, with the three facts that are
--     properties of the PAIR rather than of either side - what this supplier
--     charges for this item, the minimum they will ship, and how long they take.
--     A supplier's own lead_time_days on `suppliers` stays as the default for a
--     pair that does not override it.
--
--  2. A foreign key from purchase_order onto the supplier it names. The column
--     is text and stays text: `suppliers.supplier_key` ('sup-2') is the id the
--     whole product uses on the wire, and the uuid deliberately never appears
--     there (see docs/decisions.md, "GET /suppliers/{id} and friends take the
--     frontend's id"). So the key is the right referent - it simply was never
--     enforced, which left `po.supplier_id` free to name a supplier that does
--     not exist.
--
--  Conventions from V1 apply: uuid v7 primary keys, tenant_id first in every
--  composite index, created_at / updated_at / version on every row, updated_at
--  by trigger, and app.enable_tenant_rls() for the isolation policy.
-- ============================================================================

-- ---------------------------------------------------------------------------
--  supplier_products
--
--  One row per (supplier, product) a tenant has decided is a real option.
--
--  Both sides cascade on delete: the row is the relation and nothing else, so
--  it has no meaning once either end is gone. The unique key carries tenant_id
--  as well as the pair - supplier_id and product_id are already tenant-scoped
--  by their own foreign keys, but a composite unique index led by tenant_id is
--  what every read here is predicated on.
--
--  ex_works is numeric, never double: it is money. moq and lead_time_days are
--  nullable because "we have not recorded one" is a different fact from zero,
--  and the engine falls back to the supplier's own lead time when it is null.
-- ---------------------------------------------------------------------------
CREATE TABLE supplier_products (
    id              uuid          PRIMARY KEY DEFAULT app.uuid_generate_v7(),
    tenant_id       uuid          NOT NULL REFERENCES tenants (id)   ON DELETE CASCADE,
    supplier_id     uuid          NOT NULL REFERENCES suppliers (id) ON DELETE CASCADE,
    product_id      uuid          NOT NULL REFERENCES products (id)  ON DELETE CASCADE,
    ex_works        numeric(14,4),
    moq             integer,
    lead_time_days  integer,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    version         bigint        NOT NULL DEFAULT 0,

    CONSTRAINT supplier_products_ex_works_ck CHECK (ex_works IS NULL OR ex_works >= 0),
    CONSTRAINT supplier_products_moq_ck      CHECK (moq IS NULL OR moq >= 0),
    CONSTRAINT supplier_products_lead_ck     CHECK (lead_time_days IS NULL OR lead_time_days >= 0)
);

CREATE UNIQUE INDEX supplier_products_tenant_pair_uk
    ON supplier_products (tenant_id, supplier_id, product_id);

-- The buy-side read: "who carries this item", so product first after tenant.
CREATE INDEX supplier_products_tenant_product_idx
    ON supplier_products (tenant_id, product_id);

-- The suppliers-side read: "what does this supplier carry".
CREATE INDEX supplier_products_tenant_supplier_idx
    ON supplier_products (tenant_id, supplier_id);

CREATE TRIGGER supplier_products_touch_updated_at
    BEFORE UPDATE ON supplier_products
    FOR EACH ROW EXECUTE FUNCTION app.touch_updated_at();

SELECT app.enable_tenant_rls('supplier_products');

COMMENT ON TABLE supplier_products IS
    'Which suppliers can quote on which products, and the per-pair commercial facts.';
COMMENT ON COLUMN supplier_products.ex_works IS
    'This supplier''s ex-works price for this item. Null means "not quoted"; the engine derives one.';
COMMENT ON COLUMN supplier_products.lead_time_days IS
    'Overrides suppliers.lead_time_days for this item. Null means use the supplier''s own.';

-- ---------------------------------------------------------------------------
--  purchase_order -> suppliers, on the natural key
--
--  (tenant_id, supplier_id) references (tenant_id, supplier_key), which
--  suppliers_tenant_key_uk already covers. Carrying tenant_id into the key is
--  not decoration: without it a purchase order could name a supplier belonging
--  to another tenant and the constraint would be satisfied.
--
--  ON DELETE RESTRICT, not CASCADE: a purchase order is a record of something
--  that happened. Removing a supplier must not silently delete the history of
--  what was bought from them - it should fail and make someone decide.
-- ---------------------------------------------------------------------------
ALTER TABLE purchase_order
    ADD CONSTRAINT purchase_order_supplier_fk
    FOREIGN KEY (tenant_id, supplier_id)
    REFERENCES suppliers (tenant_id, supplier_key)
    ON DELETE RESTRICT;
