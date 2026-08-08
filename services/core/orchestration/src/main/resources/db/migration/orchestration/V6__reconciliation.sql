-- Invariant 6 gets a table: Core and the Ledger agree, and disagreement is a record.
--
-- testing.md has said since v1.0 that reconciliation "runs as a scheduled job"; until now the
-- job did not exist. The findings live in a table rather than a log because a discrepancy
-- between Core's saga record and the ledger's monetary record is exactly the kind of fact an
-- examiner asks for by date range, and a log line is a fact with a retention policy.
CREATE TABLE orchestration.reconciliation_findings (
    id           BIGSERIAL   PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    saga_id      UUID        NOT NULL,
    kind         TEXT        NOT NULL CHECK (kind IN ('LEDGER_MISSING', 'AMOUNT_MISMATCH')),
    -- What was expected and what was found, as text — this is evidence, not state, and it must
    -- still read correctly after the schema that produced it moves on.
    detail       TEXT        NOT NULL,
    found_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, saga_id) REFERENCES orchestration.sagas (tenant_id, id),
    -- One finding per saga per kind: the scheduled job re-examines its window on every run, and
    -- an unresolved discrepancy must not multiply into one row per hour.
    CONSTRAINT one_finding_per_saga_per_kind UNIQUE (saga_id, kind)
);

-- Findings are evidence: append-only, like attempt history.
CREATE TRIGGER reconciliation_findings_are_append_only
    BEFORE UPDATE OR DELETE ON orchestration.reconciliation_findings
    FOR EACH ROW EXECUTE FUNCTION orchestration.reject_mutation();

ALTER TABLE orchestration.reconciliation_findings ENABLE ROW LEVEL SECURITY;
ALTER TABLE orchestration.reconciliation_findings FORCE ROW LEVEL SECURITY;

CREATE POLICY reconciliation_findings_tenant_isolation ON orchestration.reconciliation_findings
    USING (tenant_id = orchestration.current_tenant())
    WITH CHECK (tenant_id = orchestration.current_tenant());

-- The reconciler runs as the worker — it scans every tenant's terminal sagas, so like the saga
-- worker it gets a policy rather than BYPASSRLS.
GRANT SELECT, INSERT ON orchestration.reconciliation_findings TO core_worker;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA orchestration TO core_worker;

CREATE POLICY reconciliation_findings_worker_access ON orchestration.reconciliation_findings
    USING (current_user = 'core_worker')
    WITH CHECK (current_user = 'core_worker');

-- A reconciliation mismatch is an ops case: someone must look, and the queue operators already
-- watch is where it must appear.
ALTER TABLE orchestration.ops_cases DROP CONSTRAINT ops_cases_kind_check;
ALTER TABLE orchestration.ops_cases
    ADD CONSTRAINT ops_cases_kind_check
    CHECK (kind IN ('UNRESOLVED_OUTCOME', 'RECONCILIATION_MISMATCH'));

-- One open case per saga, arbitrated by the index rather than by the job being careful — the
-- reconciler re-examines its window hourly, and an unfixed mismatch must not open a case per
-- run. (Nothing before this migration could violate it: escalation opens at most one case per
-- saga because a saga escalates once.)
CREATE UNIQUE INDEX one_open_case_per_saga
    ON orchestration.ops_cases (saga_id)
    WHERE status = 'OPEN';
