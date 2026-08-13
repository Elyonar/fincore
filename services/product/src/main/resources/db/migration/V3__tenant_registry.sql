-- What this schema needs now that it is a deployable rather than a module inside Core.
--
-- Two things changed on extraction and neither is about the catalogue itself:
--
--   1. The tenant registry. Inside Core, `platform.tenants` answered "is this tenant real?" for
--      every module and Core's TenantGate enforced it once for all of them. This service has its
--      own database and cannot see that table, so it needs its own — the same shape the ledger,
--      Notification and Identity each keep, for the same reason. Row-level security isolates
--      tenants from one another and has nothing to say about whether a tenant exists at all: any
--      UUID in a validated token was otherwise a working tenant with its own catalogue.
--
--   2. The grants. `core_product` was the role that served this schema and does not exist in this
--      database; `product_app` does.
--
-- Deliberately NOT row-level secured: a request must be able to ask "is this tenant real?" before
-- it has a tenant context to be scoped by.
CREATE TABLE product.tenants (
    id         UUID        PRIMARY KEY,
    name       TEXT        NOT NULL,
    status     TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by TEXT        NOT NULL
);

COMMENT ON TABLE product.tenants IS
    'Provisioned deliberately, never implied by a token. Holds a name and a status — no money, '
    'no PII, no catalogue.';

GRANT SELECT ON product.tenants TO product_app;
