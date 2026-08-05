-- Two things the design specified and the implementation never had.
--
-- 1. A tenant registry. design.md records "tenant provisioning → versioned seed
--    script", and data-model.md says tenant_config is "seeded by the
--    provisioning script". Neither existed, and TenantConfigService falls back
--    to platform defaults when no config row matches — so *any* UUID in a
--    request header was a valid tenant with working defaults. Row-level
--    security isolated tenants from each other perfectly and never asked
--    whether a tenant was real.
--
-- 2. A ledger epoch. architecture.md specifies the restore protocol: every
--    published event carries an epoch, a restore increments it, and consumers
--    discard events from an epoch newer than the one they were restored to.
--    Without it, a restore silently republishes ids that consumers have already
--    seen under different content, and nothing downstream can tell.

CREATE TABLE tenants (
    id         UUID        PRIMARY KEY,
    name       TEXT        NOT NULL,
    status     TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by TEXT        NOT NULL
);

COMMENT ON TABLE tenants IS
    'The registry a tenant must appear in before it can hold money. Provisioned deliberately, '
    'never implied by a header. Deliberately NOT row-level secured: a request must be able to ask '
    '"is this tenant real?" before it has a tenant context to be scoped by.';

-- Existing rows predate the registry. Backfill from what is already in use, so
-- the constraint below can be trusted rather than merely declared.
INSERT INTO tenants (id, name, created_by)
SELECT DISTINCT tenant_id, 'backfilled from existing accounts', 'migration:V6'
  FROM accounts
ON CONFLICT (id) DO NOTHING;

INSERT INTO tenants (id, name, created_by)
SELECT DISTINCT tenant_id, 'backfilled from existing config', 'migration:V6'
  FROM tenant_config
ON CONFLICT (id) DO NOTHING;

ALTER TABLE accounts       ADD CONSTRAINT accounts_tenant_registered
    FOREIGN KEY (tenant_id) REFERENCES tenants (id);
ALTER TABLE tenant_config  ADD CONSTRAINT tenant_config_tenant_registered
    FOREIGN KEY (tenant_id) REFERENCES tenants (id);

-- ---------------------------------------------------------------------------
-- Ledger epoch
-- ---------------------------------------------------------------------------

CREATE TABLE ledger_epoch (
    singleton BOOLEAN     PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    epoch     BIGINT      NOT NULL DEFAULT 1 CHECK (epoch > 0),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reason    TEXT        NOT NULL DEFAULT 'initial'
);

COMMENT ON TABLE ledger_epoch IS
    'One row, enforced by the singleton primary key. Incremented on restore from backup, never '
    'otherwise. Every published event carries the current value so a consumer can discard events '
    'from an epoch it has already been told to distrust.';

INSERT INTO ledger_epoch (singleton, epoch, reason) VALUES (TRUE, 1, 'initial');

-- Restores are rare, deliberate, and must be attributed. A procedure rather
-- than a bare UPDATE so the reason is not optional.
CREATE OR REPLACE FUNCTION advance_ledger_epoch(restore_reason TEXT) RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE
    next_epoch BIGINT;
BEGIN
    IF restore_reason IS NULL OR btrim(restore_reason) = '' THEN
        RAISE EXCEPTION 'advancing the ledger epoch requires a reason';
    END IF;
    UPDATE ledger_epoch
       SET epoch = epoch + 1, changed_at = now(), reason = restore_reason
     WHERE singleton
    RETURNING epoch INTO next_epoch;
    RETURN next_epoch;
END;
$$;

GRANT SELECT, INSERT, UPDATE, DELETE ON tenants TO ledger_app;
GRANT SELECT ON ledger_epoch TO ledger_app;
