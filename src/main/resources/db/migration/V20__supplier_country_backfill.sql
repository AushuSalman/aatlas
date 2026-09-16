-- ============================================================================
--  V20  Supplier country, canonicalised
--
--  A supplier's country is not a label. It is the key to the shipping lane,
--  and through the lane to the inbound freight percentage, the duty rate and
--  the transit days; it also decides the currency they quote in.
--  LogisticsEngine.laneFor looks it up with an exact-match getOrDefault, so
--  "GB" and "UK" are not the same supplier to it. "GB" misses every row in
--  logistics_origins and falls to FALLBACK_ORIGIN - an ocean crossing with a
--  5% MFN duty and a thirty-day transit - while Currencies.forCountry falls to
--  its own default and quotes them in USD.
--
--  Neither failure shows up anywhere. The landed cost simply comes out wrong,
--  on every quote that supplier is part of, for as long as the row exists.
--
--  The alias table that prevents this was written for the CSV import and lived
--  inside its validator, so a supplier that arrived through a file was fixed
--  and the same supplier typed into the form was not. That is now shared
--  (suppliers.internal.Countries) and applied on every write - but only on
--  writes from here on. This migration is the rows that already exist.
--
--  Two columns and no more:
--
--    country   canonicalised through the same alias table as Countries.ALIASES
--    currency  recomputed from it, exactly as Currencies.forCountry would
--
--  supplier_key is deliberately NOT touched. V8 says why: every seeded figure
--  the Buy and Suppliers screens derive for a supplier - defect rate, stock
--  position, risk, terms - is a hash of that key. Recomputing it from the
--  corrected country would silently change all of them and break the ids the
--  frontend holds. The key is an identity; the country is a fact about the
--  supplier, and only the fact was wrong.
--
--  supplier_terms is likewise left as computed. TermsScoring only asks whether
--  the country is the USA, and it already accepts "us"/"usa"/"united states",
--  so canonicalising those changes nothing. The one row that would genuinely
--  flip is a supplier entered as "America", which this migration makes
--  domestic without recomputing terms - rare, and not worth reimplementing a
--  seeded hash in SQL to chase.
-- ============================================================================

-- ---------------------------------------------------------------------------
--  Row-level security
--
--  suppliers carries FORCE ROW LEVEL SECURITY (V8, via app.enable_tenant_rls)
--  and the policy compares tenant_id against app.current_tenant(), which
--  returns NULL on a migration connection - by design, so maintenance work is
--  "not blocked". But NULL makes `tenant_id = app.current_tenant()` evaluate
--  to NULL rather than true, so the rows are not visible and an UPDATE here
--  would match nothing and report success. A backfill that silently does
--  nothing is the worst outcome available, so RLS is lifted explicitly for
--  the duration.
--
--  Safe because Postgres DDL is transactional and Flyway runs each migration
--  in one transaction: if anything below fails, the DISABLE rolls back with
--  it and the table is never left unprotected. FORCE is re-applied by name
--  rather than assumed, because DISABLE and FORCE are separate flags.
--
--  (V17's `UPDATE stores SET source = 'sample'` has the same shape and did
--  match nothing. It is harmless only because SampleCatalogueSeeder sets that
--  value itself. Worth a look separately.)
-- ---------------------------------------------------------------------------
ALTER TABLE suppliers DISABLE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
--  country
--
--  The comparison key is letters and digits only, lowercased, so "United
--  Kingdom", "united-kingdom" and "UnitedKingdom" are one country - the same
--  normalisation Countries.normalise applies in Java.
--
--  A country with no alias is left exactly as written. The platform has no
--  opinion about how France is spelt, only about the eight it prices lanes
--  for; a supplier outside them is genuinely unlisted, and the fallback origin
--  says so honestly.
-- ---------------------------------------------------------------------------
UPDATE suppliers SET country = canonical
FROM (VALUES
    ('us', 'USA'), ('usa', 'USA'), ('unitedstates', 'USA'),
    ('unitedstatesofamerica', 'USA'), ('america', 'USA'),
    ('uk', 'UK'), ('gb', 'UK'), ('unitedkingdom', 'UK'),
    ('greatbritain', 'UK'), ('england', 'UK'),
    ('de', 'Germany'), ('germany', 'Germany'), ('deutschland', 'Germany'),
    ('cn', 'China'), ('china', 'China'), ('prc', 'China'),
    ('in', 'India'), ('india', 'India'),
    ('vn', 'Vietnam'), ('vietnam', 'Vietnam'), ('viet', 'Vietnam'),
    ('mx', 'Mexico'), ('mexico', 'Mexico'),
    ('ca', 'Canada'), ('canada', 'Canada')
) AS alias (key, canonical)
WHERE lower(regexp_replace(suppliers.country, '[^a-zA-Z0-9]', '', 'g')) = alias.key
  AND suppliers.country IS DISTINCT FROM alias.canonical;

-- ---------------------------------------------------------------------------
--  currency
--
--  Recomputed for every row rather than only the ones just corrected, which
--  makes this idempotent and self-healing: currency is never sent by a client
--  and never edited by hand - AddSupplierRequest and PatchSupplierRequest have
--  no such field - so it is always derivable, and any row disagreeing with its
--  own country is wrong by definition.
--
--  The CASE is Currencies.forCountry, arm for arm, matched on the stripped and
--  lowercased country exactly as the Java does (spaces kept, so "united
--  states" matches its arm there as it does here).
-- ---------------------------------------------------------------------------
UPDATE suppliers SET currency = CASE lower(btrim(country))
    WHEN 'usa' THEN 'USD'
    WHEN 'us' THEN 'USD'
    WHEN 'united states' THEN 'USD'
    WHEN 'uk' THEN 'GBP'
    WHEN 'united kingdom' THEN 'GBP'
    WHEN 'england' THEN 'GBP'
    WHEN 'scotland' THEN 'GBP'
    WHEN 'wales' THEN 'GBP'
    WHEN 'germany' THEN 'EUR'
    WHEN 'france' THEN 'EUR'
    WHEN 'italy' THEN 'EUR'
    WHEN 'spain' THEN 'EUR'
    WHEN 'netherlands' THEN 'EUR'
    WHEN 'belgium' THEN 'EUR'
    WHEN 'austria' THEN 'EUR'
    WHEN 'ireland' THEN 'EUR'
    WHEN 'poland' THEN 'EUR'
    WHEN 'canada' THEN 'CAD'
    WHEN 'australia' THEN 'AUD'
    WHEN 'india' THEN 'INR'
    WHEN 'mexico' THEN 'MXN'
    WHEN 'china' THEN 'CNY'
    WHEN 'vietnam' THEN 'VND'
    WHEN 'japan' THEN 'JPY'
    ELSE 'USD'
END
WHERE currency IS DISTINCT FROM CASE lower(btrim(country))
    WHEN 'usa' THEN 'USD'
    WHEN 'us' THEN 'USD'
    WHEN 'united states' THEN 'USD'
    WHEN 'uk' THEN 'GBP'
    WHEN 'united kingdom' THEN 'GBP'
    WHEN 'england' THEN 'GBP'
    WHEN 'scotland' THEN 'GBP'
    WHEN 'wales' THEN 'GBP'
    WHEN 'germany' THEN 'EUR'
    WHEN 'france' THEN 'EUR'
    WHEN 'italy' THEN 'EUR'
    WHEN 'spain' THEN 'EUR'
    WHEN 'netherlands' THEN 'EUR'
    WHEN 'belgium' THEN 'EUR'
    WHEN 'austria' THEN 'EUR'
    WHEN 'ireland' THEN 'EUR'
    WHEN 'poland' THEN 'EUR'
    WHEN 'canada' THEN 'CAD'
    WHEN 'australia' THEN 'AUD'
    WHEN 'india' THEN 'INR'
    WHEN 'mexico' THEN 'MXN'
    WHEN 'china' THEN 'CNY'
    WHEN 'vietnam' THEN 'VND'
    WHEN 'japan' THEN 'JPY'
    ELSE 'USD'
END;

ALTER TABLE suppliers ENABLE ROW LEVEL SECURITY;
ALTER TABLE suppliers FORCE ROW LEVEL SECURITY;

COMMENT ON COLUMN suppliers.country IS
    'Canonical spelling - the key to logistics_origins and to the quoting currency, not a display label. '
    'Written through suppliers.internal.Countries.canonical; a country with no lane on file is kept as '
    'written and costed at the fallback origin.';
