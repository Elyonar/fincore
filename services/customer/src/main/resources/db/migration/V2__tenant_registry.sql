-- The registry a tenant must appear in before this service will serve it.
--
-- Inside Core, `platform.tenants` answered "is this tenant real?" for every module at once and
-- Core's gate enforced it. This service has its own database and cannot see that table, so it keeps
-- its own — the same arrangement Ledger, Identity and Notification each landed on independently.
--
-- Row-level security isolates tenants from one another and has nothing to say about whether a
-- tenant exists at all. Without this, any UUID in a validated token was a working institution with
-- no customers, which reads to an operator as "nobody registered yet" rather than as "this
-- institution does not exist here". On the service that holds the platform's only PII, that
-- distinction is worth more than it is anywhere else: an unregistered tenant getting an empty
-- customer list is indistinguishable from a real one, and the difference matters to an auditor.
--
-- Deliberately NOT row-level secured: a request must be able to ask "is this tenant real?" before
-- it has a tenant context to be scoped by.
CREATE TABLE customer.tenants (
    id         UUID        PRIMARY KEY,
    name       TEXT        NOT NULL,
    status     TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by TEXT        NOT NULL
);

COMMENT ON TABLE customer.tenants IS
    'Provisioned deliberately, never implied by a token. Holds a name and a status — no PII.';

GRANT SELECT ON customer.tenants TO customer_app;
