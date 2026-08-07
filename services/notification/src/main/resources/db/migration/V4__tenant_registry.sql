-- The registry a tenant must appear in before this service will serve or notify it.
--
-- Same reasoning as the ledger's V6 and Core's platform/V1: row-level security isolates tenants
-- from one another and has nothing to say about whether a tenant is real. Any UUID in a validated
-- token — or in an event envelope — was a working tenant, with its own templates, its own policy
-- and its own queue.
--
-- Deliberately NOT row-level secured: a request must be able to ask "is this tenant real?" before
-- it has a tenant context to be scoped by.
CREATE TABLE notification.tenants (
    id         UUID        PRIMARY KEY,
    name       TEXT        NOT NULL,
    status     TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by TEXT        NOT NULL
);

COMMENT ON TABLE notification.tenants IS
    'Provisioned deliberately, never implied by a token or an event. Holds a name and a status — '
    'no money, no PII.';

-- Backfill, so an existing deployment does not refuse the tenants it is already serving. A
-- security fix that silently drops live traffic is a data-loss bug wearing better clothes.
INSERT INTO notification.tenants (id, name, created_by)
SELECT DISTINCT tenant_id, 'backfilled from existing policy', 'migration:V4'
  FROM notification.channel_policy
ON CONFLICT (id) DO NOTHING;

INSERT INTO notification.tenants (id, name, created_by)
SELECT DISTINCT tenant_id, 'backfilled from existing templates', 'migration:V4'
  FROM notification.templates
ON CONFLICT (id) DO NOTHING;

-- Read to the request path and the worker; writing is provisioning's job, done as the owner.
GRANT SELECT ON notification.tenants TO notification_app, notification_worker;
