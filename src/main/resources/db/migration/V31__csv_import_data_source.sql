-- ============================================================================
--  V31  A committed CSV import is the workspace's data source
--
--  Until now a CSV import loaded the history but wrote no data_sources row, so
--  /me reported "no data" for the workspace. The browser that ran the import
--  papered over it with what it remembered; anyone else - an invited colleague,
--  the same person on another device - was sent back to "Connect your data".
--  The import commit now records the source (CsvSourceRecorder); this backfills
--  the workspaces that imported before it did.
--
--  One csv row per workspace (data_sources_csv_uk, V22), describing its latest
--  committed upload. Sample loads are not a data source, as in CsvSourceRecorder.
--
--  Both tables are under FORCE ROW LEVEL SECURITY, and a migration has no tenant
--  bound, so it would read and write nothing. RLS is lifted for this transaction
--  only, as V20 does, and restored - ENABLE and FORCE both - before it commits.
-- ============================================================================

ALTER TABLE data_sources   DISABLE ROW LEVEL SECURITY;
ALTER TABLE import_batches DISABLE ROW LEVEL SECURITY;

INSERT INTO data_sources (tenant_id, kind, label, detail, status, last_sync_at, connected_at, connected_by)
SELECT latest.tenant_id,
       'csv',
       'CSV imports',
       latest.file_name || ' · ' || to_char(latest.loaded_rows, 'FM999,999,999') || ' rows'
           || CASE WHEN latest.months_covered > 0 THEN ' · ' || latest.months_covered || ' months' ELSE '' END,
       'connected',
       latest.committed_at,
       latest.committed_at,
       latest.uploaded_by
  FROM (SELECT DISTINCT ON (b.tenant_id) b.*
          FROM import_batches b
         WHERE b.status = 'COMMITTED' AND b.source = 'upload'
         ORDER BY b.tenant_id, b.committed_at DESC) latest
 WHERE NOT EXISTS (SELECT 1 FROM data_sources d WHERE d.tenant_id = latest.tenant_id AND d.kind = 'csv');

ALTER TABLE data_sources   ENABLE ROW LEVEL SECURITY;
ALTER TABLE data_sources   FORCE ROW LEVEL SECURITY;
ALTER TABLE import_batches ENABLE ROW LEVEL SECURITY;
ALTER TABLE import_batches FORCE ROW LEVEL SECURITY;
