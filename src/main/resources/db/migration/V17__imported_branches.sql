-- ============================================================================
--  V17  Branches that arrive from an import
--
--  A CSV export names a branch by code and says nothing about where it is. The
--  region is not derivable from "200110", and the four market regions drive the
--  map, the regional rollups and part of the pricing signal - so guessing one
--  puts a branch in the wrong market and nobody ever finds out, because the
--  answer looks reasonable.
--
--  So the schema learns to say "not known yet". An imported branch is created
--  with region_key = 'unassigned', which is a real state rather than a
--  placeholder: it opens the catalogue, keeps the branch out of anything
--  regional until a human places it, and is visible on screen as work to do.
--
--  Deliberately NOT added to the regions reference table. Every regional query
--  joins that table, so leaving it out is what keeps unassigned branches from
--  appearing as a fifth region on the map.
-- ============================================================================

ALTER TABLE stores DROP CONSTRAINT stores_region_ck;

ALTER TABLE stores ADD CONSTRAINT stores_region_ck
    CHECK (region_key IN ('south', 'west', 'north', 'east', 'unassigned'));

-- ---------------------------------------------------------------------------
--  source
--
--  Where the branch came from, so the UI can tell "this arrived from your
--  import and needs a region" apart from "someone created this and chose
--  unassigned on purpose". Both are unassigned; only one is a prompt.
-- ---------------------------------------------------------------------------
ALTER TABLE stores ADD COLUMN source text NOT NULL DEFAULT 'manual';

ALTER TABLE stores ADD CONSTRAINT stores_source_ck
    CHECK (source IN ('manual', 'import', 'erp', 'sample'));

-- Everything that exists today was seeded or hand-made, not imported.
UPDATE stores SET source = 'sample' WHERE source = 'manual';

-- The "branches needing a region" list the connect screen shows after an import.
CREATE INDEX stores_tenant_unassigned_idx
    ON stores (tenant_id, store_code)
    WHERE region_key = 'unassigned';

-- ---------------------------------------------------------------------------
--  The import report's branch column changes meaning
--
--  Branches are now created rather than left unmatched, so "unresolved" is the
--  wrong word for what the column holds. It still lists branch codes from the
--  file - but as work to finish, not as data that was dropped.
-- ---------------------------------------------------------------------------
ALTER TABLE import_batches RENAME COLUMN unresolved_branches TO branches_needing_region;

ALTER TABLE import_batches ADD COLUMN branches_created integer;

COMMENT ON COLUMN import_batches.branches_needing_region IS
    'Branch codes this import created with no region. Customers stay genuinely unresolved; branches do not.';

COMMENT ON COLUMN stores.region_key IS
    'One of the four market regions, or ''unassigned'' for a branch imported before anyone placed it.';
COMMENT ON COLUMN stores.source IS
    'How this branch came to exist. ''import'' plus ''unassigned'' is the prompt to place it.';
