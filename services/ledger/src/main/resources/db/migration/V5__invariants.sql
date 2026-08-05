-- Invariant verification: anchors and run reports.
--
-- Full sums over seven years of entries cannot run hourly on the operational
-- primary forever (~17M entries/day at 200 TPS), so verification is
-- incremental: an immutable daily anchor per account, proven once when written,
-- and hourly checks that verify `anchor + Σ(entries since anchor) = balance`.

CREATE TABLE balance_anchors (
    tenant_id            UUID        NOT NULL,
    account_id           UUID        NOT NULL,
    captured_on          DATE        NOT NULL,

    -- The anchor keys on PHYSICAL insertion order, never on value_date.
    -- "Daily" means captured daily; it is a checkpoint of physical history, not
    -- a business-date balance. This is what makes backdating harmless here: a
    -- backdated posting is a new entry above every existing bound, so it lands
    -- in the current delta window like any other, and cannot falsify an anchor
    -- that was already proven.
    entry_id_upper_bound BIGINT      NOT NULL,
    balance_minor        BIGINT      NOT NULL,
    captured_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, account_id, captured_on),
    FOREIGN KEY (tenant_id, account_id) REFERENCES accounts (tenant_id, id)
);

COMMENT ON COLUMN balance_anchors.entry_id_upper_bound IS
    'Finalized only at an MVCC quiesce horizon: every write transaction older than the capture '
    'snapshot has completed. Without that, a late-committing entry could fall below the bound '
    'after it was proven, and the anchor would be quietly wrong forever.';

CREATE TABLE invariant_runs (
    id            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     UUID        NOT NULL,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ,
    scope         TEXT        NOT NULL CHECK (scope IN ('INCREMENTAL','FULL')),

    -- Violations are bugs and page. Authorized exposures are the routine,
    -- explained consequence of a reversal bypassing the negative-balance guard;
    -- they are tracked and aged, never alarmed on. Keeping them apart is what
    -- lets "zero violations" stay both achievable and meaningful — an alarm
    -- that fires routinely is an alarm people learn to ignore.
    violations    INT         NOT NULL DEFAULT 0,
    exposures     INT         NOT NULL DEFAULT 0,
    findings      JSONB       NOT NULL DEFAULT '[]'::jsonb
);

CREATE INDEX invariant_runs_latest ON invariant_runs (tenant_id, started_at DESC);

ALTER TABLE balance_anchors ENABLE ROW LEVEL SECURITY;
ALTER TABLE balance_anchors FORCE ROW LEVEL SECURITY;
ALTER TABLE invariant_runs  ENABLE ROW LEVEL SECURITY;
ALTER TABLE invariant_runs  FORCE ROW LEVEL SECURITY;

CREATE POLICY balance_anchors_tenant_isolation ON balance_anchors
    USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE POLICY invariant_runs_tenant_isolation ON invariant_runs
    USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());

-- Anchors are proven once and then relied upon; editing one would invalidate
-- every incremental check that has trusted it since.
CREATE TRIGGER balance_anchors_are_immutable
    BEFORE UPDATE OR DELETE ON balance_anchors
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

GRANT SELECT, INSERT, UPDATE, DELETE ON balance_anchors, invariant_runs TO ledger_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ledger_app;
