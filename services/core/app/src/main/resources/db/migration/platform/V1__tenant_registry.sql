-- The registry a tenant must appear in before Core will serve it.
--
-- Row-level security isolates tenants from one another and has nothing to say about whether a
-- tenant is *real*. Until now any UUID in a validated token was a working tenant: it got its own
-- empty, functioning slice of Core, every isolation test passed, and a typo in provisioning would
-- have produced a parallel universe of data that nobody noticed until someone asked why a bank's
-- customers had disappeared. The ledger hit exactly this and fixed it in its V6; this is the same
-- medicine, applied at the second deployable that needed it.
--
-- It lives in its own schema rather than in a module's, because it is a fact about the deployable
-- and not about customer, product or orchestration. A module owning it would make the other two
-- read another module's table, which is the one thing ADR 0006 forbids outright.

CREATE SCHEMA IF NOT EXISTS platform;

CREATE TABLE platform.tenants (
    id         UUID        PRIMARY KEY,
    name       TEXT        NOT NULL,
    status     TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by TEXT        NOT NULL
);

COMMENT ON TABLE platform.tenants IS
    'The registry a tenant must appear in before Core will serve it. Provisioned deliberately, '
    'never implied by a token. Deliberately NOT row-level secured: a request must be able to ask '
    '"is this tenant real?" before it has a tenant context to be scoped by. Holds a name and a '
    'status — no money, no PII.';

-- Backfill from what is already here, so the check below can be trusted rather than merely
-- declared. A deployment that has been running has real tenants in it, and refusing them all on
-- the next restart would be a data-loss bug wearing a security fix's clothes.
INSERT INTO platform.tenants (id, name, created_by)
SELECT DISTINCT tenant_id, 'backfilled from existing customers', 'migration:platform/V1'
  FROM customer.customers
ON CONFLICT (id) DO NOTHING;

INSERT INTO platform.tenants (id, name, created_by)
SELECT DISTINCT tenant_id, 'backfilled from existing sagas', 'migration:platform/V1'
  FROM orchestration.sagas
ON CONFLICT (id) DO NOTHING;

INSERT INTO platform.tenants (id, name, created_by)
SELECT DISTINCT tenant_id, 'backfilled from existing products', 'migration:platform/V1'
  FROM product.products
ON CONFLICT (id) DO NOTHING;

-- Read-only to every module role. The gate runs once per request, before any handler, so which
-- role performs the read is an implementation detail — but writing is provisioning's job and no
-- module has any business doing it.
GRANT USAGE ON SCHEMA platform TO core_customer, core_product, core_orchestration, core_worker;
GRANT SELECT ON platform.tenants TO core_customer, core_product, core_orchestration, core_worker;
