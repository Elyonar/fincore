-- Notification's schema, roles and tenant function.
--
-- Migrations run as the owner; traffic connects as a restricted role (service-scaffold §4). DDL
-- and traffic are different jobs and must not share an identity — and the restricted roles are
-- NOBYPASSRLS, because PostgreSQL exempts a BYPASSRLS role from row-level security entirely while
-- the catalog still reports every policy enabled.

CREATE SCHEMA IF NOT EXISTS notification;

-- Created without a password. Local development gives them one in db/init; production provisions
-- the same names with a real secret. A password committed here would be a password in the
-- repository, which is the one place it must never be.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'notification_app') THEN
        CREATE ROLE notification_app NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'notification_worker') THEN
        CREATE ROLE notification_worker NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
END;
$$;

GRANT USAGE ON SCHEMA notification TO notification_app, notification_worker;

-- The tenant of the current transaction, or NULL when none has been set.
--
-- Read by every policy below. `SET LOCAL` inside the transaction, never a session SET: connections
-- are pooled across tenants, and a session variable would hand the next borrower the previous
-- tenant's identity — the failure row-level security exists to catch.
CREATE OR REPLACE FUNCTION notification.current_tenant() RETURNS UUID
LANGUAGE plpgsql STABLE AS $$
DECLARE
    raw TEXT := current_setting('app.tenant_id', true);
BEGIN
    IF raw IS NULL OR raw = '' THEN
        RETURN NULL;
    END IF;
    RETURN raw::UUID;
END;
$$;

-- Whether this transaction is the send worker's.
--
-- The worker claims queued messages across every tenant, so it has no tenant to be scoped by.
-- Rather than exempting it from row-level security wholesale, it opts into a flag that a second,
-- permissive policy reads — the same shape Core's outbox relay uses. The grant stays narrow and
-- every other table remains closed to it.
CREATE OR REPLACE FUNCTION notification.is_worker() RETURNS BOOLEAN
LANGUAGE plpgsql STABLE AS $$
BEGIN
    RETURN coalesce(current_setting('app.worker', true), 'off') = 'on';
END;
$$;
