-- =============================================================================================
-- Roles become first-class rows.
--
-- V1 modelled a role only as its permissions: a role existed because auth.role_permissions held
-- rows naming it. Three things the administration surface owes are unrepresentable that way — a
-- role with no permissions yet, whether a role is a platform template or the tenant's own work,
-- and who created it and when (ADR 0017 guardrail 4: every change attributed).
--
-- The composition table is unchanged and stays the authority on *what* a role grants. This table
-- is the authority on *which roles exist*.
-- =============================================================================================

CREATE TABLE auth.roles (
    tenant_id  UUID        NOT NULL REFERENCES auth.tenants (id),
    -- Stored exactly as it travels in the claim. Tenant-authored names are namespaced `role:`
    -- by the service before they arrive here (ADR 0017 guardrail 0); template names keep the
    -- `job:` prefix they were seeded with.
    name       TEXT        NOT NULL,
    -- A platform starting position rather than the tenant's own composition. Templates may be
    -- recomposed and deleted like any other role once role authoring lands — the flag records
    -- provenance, so an administrator can tell what they inherited from what they wrote.
    template   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by TEXT        NOT NULL,
    created_via TEXT       NOT NULL,
    PRIMARY KEY (tenant_id, name)
);

ALTER TABLE auth.roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth.roles FORCE ROW LEVEL SECURITY;
CREATE POLICY roles_tenant ON auth.roles
    USING (tenant_id = auth.current_tenant());
GRANT SELECT, INSERT, DELETE ON auth.roles TO identity_app;

-- No backfill here on purpose. Migrations run as the owner and these tables are FORCE ROW LEVEL
-- SECURITY, so a SELECT with no tenant context reads nothing: the backfill would appear to run
-- and quietly copy zero rows. ManifestSeeder converges the templates on every boot instead,
-- which is where role provenance already comes from.
