-- Give the ledger a role that row-level security can actually constrain.
--
-- V2 marked every tenant table FORCE ROW LEVEL SECURITY and the policies were
-- still bypassed, because the service was connecting as the bootstrap role —
-- which is a SUPERUSER, and superusers bypass RLS unconditionally. FORCE closes
-- the owner exemption; nothing closes the superuser exemption. So RLS is only a
-- backstop if the runtime connects as a role that is neither.
--
-- Separation of duties, and both halves matter:
--   * migrations run as the owner   — needs DDL, must not serve traffic
--   * the service runs as ledger_app — needs DML only, and is subject to RLS
--
-- No password is set here. A credential committed to a public repository is a
-- credential to rotate, and migrations run in production too. The role is
-- created LOGIN-less; each environment grants login separately:
--   local  — db/init/10-app-role.sql, mounted by compose
--   CI     — a psql step in .github/workflows/ci.yml
--   prod   — deployment provisioning, with a real secret

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ledger_app') THEN
        CREATE ROLE ledger_app NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
END
$$;

-- Explicitly re-assert the two attributes that would silently defeat every
-- policy, in case the role already existed with different ones.
ALTER ROLE ledger_app NOSUPERUSER NOBYPASSRLS;

GRANT USAGE ON SCHEMA public TO ledger_app;

-- DML only: the service reads and writes rows, and never reshapes the schema.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ledger_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ledger_app;

-- Tables added by later migrations must not silently arrive unreachable.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ledger_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO ledger_app;

-- Deliberately withheld: DELETE is granted above because the outbox purges
-- published rows, but the append-only triggers on entries, accounting_periods
-- and tenant_config still reject it. Privileges and triggers are independent
-- defences and both stay in place.
