-- Identity's schema, role and tenant function.
--
-- Migrations run as the owner; traffic connects as a restricted role (service-scaffold §4).
-- The restricted role is NOBYPASSRLS because PostgreSQL exempts a BYPASSRLS role from row-level
-- security entirely while the catalog still reports every policy enabled.

CREATE SCHEMA IF NOT EXISTS auth;

-- Created without a password. Local development gives it one in db/init; CI in a workflow step;
-- production provisions the same name with a real secret.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'identity_app') THEN
        CREATE ROLE identity_app NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
END;
$$;

GRANT USAGE ON SCHEMA auth TO identity_app;

-- The tenant of the current transaction, or NULL when none has been set. SET LOCAL inside the
-- request transaction, never a session SET (ADR 0007): connections are pooled, and a session
-- variable would hand the next borrower the previous tenant's identity.
CREATE OR REPLACE FUNCTION auth.current_tenant() RETURNS UUID
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

-- =============================================================================================
-- Tenant registry — the same shape as the other three deployables' registries, seeded from the
-- manifest (ADR 0016, ADR 0018). Deliberately NOT row-level secured: login must be able to ask
-- "is this tenant real?" before it has a tenant context to be scoped by.
-- =============================================================================================
CREATE TABLE auth.tenants (
    id         UUID        PRIMARY KEY,
    name       TEXT        NOT NULL,
    status     TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by TEXT        NOT NULL
);
GRANT SELECT ON auth.tenants TO identity_app;

-- =============================================================================================
-- Staff. Hash material lives in auth.credentials, not here, so directory reads never touch
-- it (data-model.md). Dual attribution per service-scaffold §6.
-- =============================================================================================
CREATE TABLE auth.users (
    tenant_id            UUID        NOT NULL REFERENCES auth.tenants (id),
    id                   UUID        NOT NULL,
    username             TEXT        NOT NULL,
    email                TEXT        NOT NULL,
    first_name           TEXT        NOT NULL,
    last_name            TEXT        NOT NULL,
    status               TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    credential_temporary BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by           TEXT        NOT NULL,
    created_via          TEXT        NOT NULL,
    PRIMARY KEY (tenant_id, id)
);

-- The idempotency arbiter for user creation: an index, not application code.
CREATE UNIQUE INDEX users_username_per_tenant ON auth.users (tenant_id, lower(username));

ALTER TABLE auth.users ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth.users FORCE ROW LEVEL SECURITY;
CREATE POLICY users_tenant ON auth.users
    USING (tenant_id = auth.current_tenant());
GRANT SELECT, INSERT, UPDATE ON auth.users TO identity_app;

-- =============================================================================================
-- Credentials — one current row per user, segregated from the directory row. History holds prior
-- digests for the reuse check, bounded by the application (oldest dropped on write).
-- =============================================================================================
CREATE TABLE auth.credentials (
    tenant_id     UUID        NOT NULL,
    user_id       UUID        NOT NULL,
    password_hash TEXT        NOT NULL,
    history       TEXT[]      NOT NULL DEFAULT '{}',
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES auth.users (tenant_id, id)
);
ALTER TABLE auth.credentials ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth.credentials FORCE ROW LEVEL SECURITY;
CREATE POLICY credentials_tenant ON auth.credentials
    USING (tenant_id = auth.current_tenant());
GRANT SELECT, INSERT, UPDATE ON auth.credentials TO identity_app;

-- =============================================================================================
-- Roles: composition (role -> catalog permissions) and grants (user -> role). Composition is
-- validated against the platform permission catalog on write; an unknown string is refused in
-- the application and never stored. Role *authoring* as a product surface is Core's
-- (admin-surface §5); these tables are the directory beneath it.
-- =============================================================================================
CREATE TABLE auth.role_permissions (
    tenant_id  UUID NOT NULL REFERENCES auth.tenants (id),
    role_name  TEXT NOT NULL,
    permission TEXT NOT NULL,
    PRIMARY KEY (tenant_id, role_name, permission)
);
ALTER TABLE auth.role_permissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth.role_permissions FORCE ROW LEVEL SECURITY;
CREATE POLICY role_permissions_tenant ON auth.role_permissions
    USING (tenant_id = auth.current_tenant());
GRANT SELECT, INSERT, DELETE ON auth.role_permissions TO identity_app;

CREATE TABLE auth.user_roles (
    tenant_id UUID NOT NULL,
    user_id   UUID NOT NULL,
    role_name TEXT NOT NULL,
    PRIMARY KEY (tenant_id, user_id, role_name),
    FOREIGN KEY (tenant_id, user_id) REFERENCES auth.users (tenant_id, id)
);
ALTER TABLE auth.user_roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth.user_roles FORCE ROW LEVEL SECURITY;
CREATE POLICY user_roles_tenant ON auth.user_roles
    USING (tenant_id = auth.current_tenant());
GRANT SELECT, INSERT, DELETE ON auth.user_roles TO identity_app;

CREATE TABLE auth.user_units (
    tenant_id UUID NOT NULL,
    user_id   UUID NOT NULL,
    unit_code TEXT NOT NULL,
    PRIMARY KEY (tenant_id, user_id, unit_code),
    FOREIGN KEY (tenant_id, user_id) REFERENCES auth.users (tenant_id, id)
);
ALTER TABLE auth.user_units ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth.user_units FORCE ROW LEVEL SECURITY;
CREATE POLICY user_units_tenant ON auth.user_units
    USING (tenant_id = auth.current_tenant());
GRANT SELECT, INSERT, DELETE ON auth.user_units TO identity_app;

-- =============================================================================================
-- Sessions: a family per login, opaque rotating tokens within it (design.md D4). The presented
-- value is digested and looked up; a match on a rotated row is the theft signal that revokes the
-- family. Plaintext is never stored.
-- =============================================================================================
CREATE TABLE auth.refresh_families (
    tenant_id       UUID        NOT NULL,
    id              UUID        NOT NULL,
    user_id         UUID        NOT NULL,
    client_id       TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    absolute_expiry TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ,
    revoked_reason  TEXT CHECK (revoked_reason IN ('LOGOUT', 'ROTATION_REUSE', 'ADMIN', 'PASSWORD_CHANGE')),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES auth.users (tenant_id, id)
);
CREATE INDEX refresh_families_by_user ON auth.refresh_families (tenant_id, user_id);
ALTER TABLE auth.refresh_families ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth.refresh_families FORCE ROW LEVEL SECURITY;
CREATE POLICY refresh_families_tenant ON auth.refresh_families
    USING (tenant_id = auth.current_tenant());
GRANT SELECT, INSERT, UPDATE ON auth.refresh_families TO identity_app;

CREATE TABLE auth.refresh_tokens (
    tenant_id    UUID        NOT NULL,
    token_digest TEXT        NOT NULL,
    family_id    UUID        NOT NULL,
    issued_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    rotated_at   TIMESTAMPTZ,
    -- Replay arbitration is this index, not application code — the same rule idempotency
    -- follows everywhere on the platform.
    PRIMARY KEY (token_digest),
    FOREIGN KEY (tenant_id, family_id) REFERENCES auth.refresh_families (tenant_id, id)
);
CREATE INDEX refresh_tokens_by_family ON auth.refresh_tokens (tenant_id, family_id);
ALTER TABLE auth.refresh_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth.refresh_tokens FORCE ROW LEVEL SECURITY;
CREATE POLICY refresh_tokens_tenant ON auth.refresh_tokens
    USING (tenant_id = auth.current_tenant());
GRANT SELECT, INSERT, UPDATE ON auth.refresh_tokens TO identity_app;

-- =============================================================================================
-- Login throttling — rows, not memory, so multiple instances agree (design.md D16). Scope is
-- 'user:<lower-username>' or 'src:<source>'. Rowed for usernames that do not exist too: the
-- throttle must not be an existence oracle.
-- =============================================================================================
CREATE TABLE auth.login_throttle (
    tenant_id     UUID        NOT NULL REFERENCES auth.tenants (id),
    scope         TEXT        NOT NULL,
    failure_count INT         NOT NULL DEFAULT 0,
    window_start  TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_until  TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, scope)
);
ALTER TABLE auth.login_throttle ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth.login_throttle FORCE ROW LEVEL SECURITY;
CREATE POLICY login_throttle_tenant ON auth.login_throttle
    USING (tenant_id = auth.current_tenant());
GRANT SELECT, INSERT, UPDATE, DELETE ON auth.login_throttle TO identity_app;

-- =============================================================================================
-- Service clients — the credential behind the client-credentials flow (design.md D8). Not
-- tenant-scoped: a service credential carries no tenant claim by contract (the ledger's rule).
-- Secrets arrive digested; the plaintext is deployment configuration by reference.
-- =============================================================================================
CREATE TABLE auth.service_clients (
    client_id     TEXT        PRIMARY KEY,
    secret_digest TEXT        NOT NULL,
    enabled       BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
GRANT SELECT ON auth.service_clients TO identity_app;

-- =============================================================================================
-- The auth audit trail (design.md D11) — append-only, enforced by trigger exactly like the
-- ledger's entries, and asserted by the schema-enforcement suite. tenant_id is nullable because
-- service-token events have no tenant.
-- =============================================================================================
CREATE TABLE auth.auth_events (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       UUID,
    user_id         UUID,
    event           TEXT        NOT NULL,
    actor_principal TEXT,
    actor_service   TEXT,
    source          TEXT,
    details         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX auth_events_by_tenant_time ON auth.auth_events (tenant_id, at);

CREATE OR REPLACE FUNCTION auth.auth_events_append_only() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'auth.auth_events is append-only: % refused', TG_OP;
END;
$$;
CREATE TRIGGER auth_events_no_update BEFORE UPDATE OR DELETE ON auth.auth_events
    FOR EACH ROW EXECUTE FUNCTION auth.auth_events_append_only();

ALTER TABLE auth.auth_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth.auth_events FORCE ROW LEVEL SECURITY;
-- Tenant rows are visible in-tenant; tenantless rows (service-token events) are visible to any
-- transaction — they carry no tenant to be scoped by.
CREATE POLICY auth_events_tenant ON auth.auth_events
    USING (tenant_id = auth.current_tenant() OR tenant_id IS NULL)
    WITH CHECK (tenant_id = auth.current_tenant() OR tenant_id IS NULL);
GRANT SELECT, INSERT ON auth.auth_events TO identity_app;

-- There is deliberately no signing_keys table (data-model.md): keys are deployment-supplied by
-- reference, and the table that would make key custody a database-compromise problem is the one
-- this schema refuses to create.
