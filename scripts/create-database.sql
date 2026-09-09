-- ============================================================================
--  One-time local setup, for a PostgreSQL you already have installed.
--
--  Run as a superuser:
--    psql -U postgres -f scripts/create-database.sql
--
--  On Windows with the EnterpriseDB installer psql lives at
--    C:\Program Files\PostgreSQL\<version>\bin\psql.exe
--
--  Skip this entirely if you use docker-compose: the postgres service creates
--  the database and role from its environment.
-- ============================================================================

-- The application role. NOT a superuser: row-level security is bypassed by
-- superusers and by the table owner, which would make the isolation policies
-- silently useless in development and hide a bug that only appears in production.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'aatlas') THEN
        CREATE ROLE aatlas WITH LOGIN PASSWORD 'aatlas';
    END IF;
END
$$;

-- Flyway needs to create extensions, which requires elevated rights on most
-- installs. Granting it here keeps the migrations runnable as the app user.
ALTER ROLE aatlas CREATEDB;

SELECT 'CREATE DATABASE aatlas OWNER aatlas ENCODING ''UTF8'''
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'aatlas')
\gexec

\connect aatlas

GRANT ALL ON SCHEMA public TO aatlas;

-- pgcrypto, pg_trgm and btree_gin are created by V1__foundations.sql, which
-- needs the role to be allowed to create extensions. On a managed instance
-- (RDS, Cloud SQL) ask the platform team to pre-create them instead.
