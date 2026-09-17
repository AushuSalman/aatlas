-- ============================================================================
--  V27  Suppliers: real data
--
--  TermsScoring no longer seeds a full set of commercial terms for a supplier
--  nobody has agreed terms with. A supplier added by name and country (from a
--  lookup, a form, or a purchases import) has no terms until a person or a
--  file states them, and the honest state is NULL, not a hashed placeholder.
--  supplier_ratings and supplier_risk were already relaxed in V22; this does
--  the same for supplier_terms, which V22 did not touch.
-- ============================================================================

ALTER TABLE supplier_terms
    ALTER COLUMN credit_days              DROP NOT NULL,
    ALTER COLUMN terms_label              DROP NOT NULL,
    ALTER COLUMN early_pay_discount_pct   DROP NOT NULL,
    ALTER COLUMN early_pay_days           DROP NOT NULL,
    ALTER COLUMN late_penalty_pct_per_week DROP NOT NULL,
    ALTER COLUMN late_penalty_cap_pct     DROP NOT NULL,
    ALTER COLUMN warranty_months          DROP NOT NULL,
    ALTER COLUMN quote_validity_days      DROP NOT NULL,
    ALTER COLUMN incoterm                 DROP NOT NULL,
    ALTER COLUMN invoice_accuracy_pct     DROP NOT NULL,
    ALTER COLUMN capacity_units_month     DROP NOT NULL;

COMMENT ON COLUMN supplier_terms.credit_days IS 'NULL = not provided; no commercial terms are on file for this supplier yet.';
