-- Lending's tables (lending.md §3).
--
-- The correctness that matters is schema-enforced, as everywhere: terminal states that cannot
-- move, evidence tables that cannot be edited, chains whose rules are CHECKs and unique indexes
-- rather than application care.

CREATE OR REPLACE FUNCTION lending.current_tenant() RETURNS UUID
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

-- ---------------------------------------------------------------------------
-- Approval tiers. Ceiling → approvals required, zero permitted (lending.md §2):
-- a zero tier is instant lending under a ceiling, one is a solo lender, N is a
-- committee. Configuration, not code paths.
-- ---------------------------------------------------------------------------
CREATE TABLE lending.approval_tiers (
    id                 UUID   NOT NULL DEFAULT gen_random_uuid(),
    tenant_id          UUID   NOT NULL,
    ceiling_minor      BIGINT NOT NULL CHECK (ceiling_minor > 0),
    approvals_required INT    NOT NULL CHECK (approvals_required >= 0),
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    CONSTRAINT one_tier_per_ceiling UNIQUE (tenant_id, ceiling_minor)
);

-- ---------------------------------------------------------------------------
-- Applications. The request and its lifecycle before money; the state machine
-- of lending.md §2, with money moving at exactly one edge.
-- ---------------------------------------------------------------------------
CREATE TABLE lending.loan_applications (
    id                    UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id             UUID        NOT NULL,
    customer_id           UUID        NOT NULL,
    product_code          TEXT        NOT NULL,
    product_version       INT,
    amount_minor          BIGINT      NOT NULL CHECK (amount_minor > 0),
    term_months           INT         NOT NULL CHECK (term_months > 0),
    currency              CHAR(3)     NOT NULL,
    purpose               TEXT,
    state                 TEXT        NOT NULL DEFAULT 'APPLIED'
                                      CHECK (state IN ('APPLIED', 'APPROVED', 'OFFERED', 'ACCEPTED',
                                                       'DISBURSING', 'ACTIVE', 'CLOSED',
                                                       'REJECTED', 'WITHDRAWN', 'EXPIRED')),
    approvals_required    INT         NOT NULL CHECK (approvals_required >= 0),
    -- Attribution: who originated, where they sat (ADR 0012 snapshot). Facts, never authorization.
    applied_by            TEXT        NOT NULL,
    applied_in_unit       TEXT,
    officer               TEXT        NOT NULL,
    -- Offer economics, recorded at OFFERED so the disclosure pack renders from facts (PRD v1.9):
    -- total interest over the schedule, total cost, effective annual rate in basis points.
    offer_total_interest_minor BIGINT,
    offer_total_cost_minor     BIGINT,
    offer_effective_rate_bp    INT,
    offer_expires_at      TIMESTAMPTZ,
    -- The one funding saga this application may open. Referenced by id, no FK — another module's row.
    disbursement_saga_id  UUID,
    funding_account_id    UUID,
    destination_account_id UUID,
    last_error            TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    terminal_at           TIMESTAMPTZ,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    CONSTRAINT terminal_states_are_stamped CHECK (
        (state IN ('REJECTED', 'WITHDRAWN', 'EXPIRED', 'CLOSED') AND terminal_at IS NOT NULL)
        OR (state NOT IN ('REJECTED', 'WITHDRAWN', 'EXPIRED', 'CLOSED') AND terminal_at IS NULL)),
    CONSTRAINT offered_carries_economics CHECK (
        state NOT IN ('OFFERED', 'ACCEPTED', 'DISBURSING', 'ACTIVE', 'CLOSED')
        OR (offer_total_interest_minor IS NOT NULL AND offer_total_cost_minor IS NOT NULL
            AND offer_effective_rate_bp IS NOT NULL))
);

CREATE INDEX loan_applications_by_state ON lending.loan_applications (tenant_id, state);
CREATE INDEX loan_applications_disbursing ON lending.loan_applications (state)
    WHERE state = 'DISBURSING';

-- Terminal application states are terminal — the trigger convention.
CREATE OR REPLACE FUNCTION lending.reject_terminal_application_edit() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.terminal_at IS NOT NULL AND NEW.state IS DISTINCT FROM OLD.state THEN
        RAISE EXCEPTION 'application % is terminal (%); its state cannot change', OLD.id, OLD.state
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER loan_applications_terminal_states_are_terminal
    BEFORE UPDATE ON lending.loan_applications
    FOR EACH ROW EXECUTE FUNCTION lending.reject_terminal_application_edit();

-- ---------------------------------------------------------------------------
-- Approvals. The chain, append-only; zero-tier decisions are recorded too,
-- attributed to system:lending-policy — "nobody approved" and "the policy
-- approved" must never read alike.
-- ---------------------------------------------------------------------------
CREATE TABLE lending.loan_approvals (
    id               UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL,
    application_id   UUID        NOT NULL,
    sequence_no      INT         NOT NULL CHECK (sequence_no > 0),
    approved_by      TEXT        NOT NULL,
    approved_in_unit TEXT,
    approved_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, application_id) REFERENCES lending.loan_applications (tenant_id, id),
    -- One signature per principal per application: the index arbitrates, not code.
    CONSTRAINT one_approval_per_principal UNIQUE (application_id, approved_by),
    CONSTRAINT one_approval_per_slot UNIQUE (application_id, sequence_no)
);

CREATE OR REPLACE FUNCTION lending.reject_mutation() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'append-only: % on % is not permitted', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER loan_approvals_are_append_only
    BEFORE UPDATE OR DELETE ON lending.loan_approvals
    FOR EACH ROW EXECUTE FUNCTION lending.reject_mutation();

-- ---------------------------------------------------------------------------
-- Loans. The live obligation, created only when disbursement succeeds.
-- ---------------------------------------------------------------------------
CREATE TABLE lending.loans (
    id                        UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id                 UUID        NOT NULL,
    application_id            UUID        NOT NULL,
    customer_id               UUID        NOT NULL,
    product_code              TEXT        NOT NULL,
    product_version           INT         NOT NULL,
    principal_minor           BIGINT      NOT NULL CHECK (principal_minor > 0),
    principal_outstanding_minor BIGINT    NOT NULL CHECK (principal_outstanding_minor >= 0),
    interest_rate_bp          INT         NOT NULL,
    schedule_kind             TEXT        NOT NULL CHECK (schedule_kind IN ('ANNUITY', 'FLAT', 'BULLET')),
    currency                  CHAR(3)     NOT NULL,
    accrued_interest_minor    BIGINT      NOT NULL DEFAULT 0 CHECK (accrued_interest_minor >= 0),
    accrual_through           DATE        NOT NULL,
    disbursed_on              DATE        NOT NULL,
    funding_account_id        UUID        NOT NULL,
    customer_account_id       UUID        NOT NULL,
    officer                   TEXT        NOT NULL,
    unit_code                 TEXT,
    current_bucket            TEXT        NOT NULL DEFAULT 'CURRENT'
                                          CHECK (current_bucket IN ('CURRENT', 'DPD_1_30', 'DPD_31_60',
                                                                    'DPD_61_90', 'DPD_90_PLUS')),
    state                     TEXT        NOT NULL DEFAULT 'ACTIVE'
                                          CHECK (state IN ('ACTIVE', 'CLOSED', 'WRITTEN_OFF')),
    closed_at                 TIMESTAMPTZ,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, application_id) REFERENCES lending.loan_applications (tenant_id, id),
    -- One loan per application, arbitrated by the index — a disbursement retry must converge,
    -- never duplicate.
    CONSTRAINT one_loan_per_application UNIQUE (application_id),
    CONSTRAINT closed_loans_are_stamped CHECK (
        (state = 'ACTIVE' AND closed_at IS NULL) OR (state <> 'ACTIVE' AND closed_at IS NOT NULL))
);

CREATE INDEX loans_active ON lending.loans (tenant_id) WHERE state = 'ACTIVE';
CREATE INDEX loans_accrual_due ON lending.loans (accrual_through) WHERE state = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- Schedule. Rows, not a formula; generated once at disbursement. The paid
-- columns are the only mutable part.
-- ---------------------------------------------------------------------------
CREATE TABLE lending.loan_schedule (
    id                   UUID    NOT NULL DEFAULT gen_random_uuid(),
    tenant_id            UUID    NOT NULL,
    loan_id              UUID    NOT NULL,
    installment_no       INT     NOT NULL CHECK (installment_no > 0),
    due_date             DATE    NOT NULL,
    principal_due_minor  BIGINT  NOT NULL CHECK (principal_due_minor >= 0),
    interest_due_minor   BIGINT  NOT NULL CHECK (interest_due_minor >= 0),
    principal_paid_minor BIGINT  NOT NULL DEFAULT 0 CHECK (principal_paid_minor >= 0),
    interest_paid_minor  BIGINT  NOT NULL DEFAULT 0 CHECK (interest_paid_minor >= 0),
    settled_at           TIMESTAMPTZ,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending.loans (tenant_id, id),
    CONSTRAINT one_installment_per_slot UNIQUE (loan_id, installment_no),
    -- Paid never exceeds due: over-allocation is a bug the schema refuses, not a row that waits
    -- for reconciliation to notice.
    CONSTRAINT principal_paid_within_due CHECK (principal_paid_minor <= principal_due_minor),
    CONSTRAINT interest_paid_within_due CHECK (interest_paid_minor <= interest_due_minor)
);

-- ---------------------------------------------------------------------------
-- Repayments and their allocations. The repayment is created at intake and
-- allocated when its saga completes; allocations are evidence, append-only.
-- ---------------------------------------------------------------------------
CREATE TABLE lending.repayments (
    id                UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id         UUID        NOT NULL,
    loan_id           UUID        NOT NULL,
    amount_minor      BIGINT      NOT NULL CHECK (amount_minor > 0),
    source_account_id UUID        NOT NULL,
    repayment_saga_id UUID,
    state             TEXT        NOT NULL DEFAULT 'PENDING'
                                  CHECK (state IN ('PENDING', 'ALLOCATED', 'FAILED')),
    received_on       DATE        NOT NULL,
    idempotency_key   TEXT        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    allocated_at      TIMESTAMPTZ,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending.loans (tenant_id, id),
    -- The caller's key arbitrates duplicate submissions, per tenant, like every saga.
    CONSTRAINT repayments_idempotent UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT allocated_is_stamped CHECK (
        (state = 'ALLOCATED' AND allocated_at IS NOT NULL)
        OR (state <> 'ALLOCATED' AND allocated_at IS NULL))
);

CREATE INDEX repayments_pending ON lending.repayments (state) WHERE state = 'PENDING';

CREATE TABLE lending.repayment_allocations (
    id             UUID   NOT NULL DEFAULT gen_random_uuid(),
    tenant_id      UUID   NOT NULL,
    repayment_id   UUID   NOT NULL,
    component      TEXT   NOT NULL CHECK (component IN ('PENALTY', 'FEE', 'INTEREST', 'PRINCIPAL')),
    amount_minor   BIGINT NOT NULL CHECK (amount_minor > 0),
    installment_no INT,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, repayment_id) REFERENCES lending.repayments (tenant_id, id)
);

CREATE TRIGGER repayment_allocations_are_append_only
    BEFORE UPDATE OR DELETE ON lending.repayment_allocations
    FOR EACH ROW EXECUTE FUNCTION lending.reject_mutation();

-- ---------------------------------------------------------------------------
-- Delinquency. Bucket transitions, append-only — the classification history an
-- examiner asks for by date range.
-- ---------------------------------------------------------------------------
CREATE TABLE lending.delinquency_events (
    id            UUID  NOT NULL DEFAULT gen_random_uuid(),
    tenant_id     UUID  NOT NULL,
    loan_id       UUID  NOT NULL,
    from_bucket   TEXT  NOT NULL,
    to_bucket     TEXT  NOT NULL,
    days_past_due INT   NOT NULL CHECK (days_past_due >= 0),
    as_of         DATE  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending.loans (tenant_id, id),
    -- One transition per loan per day: the daily job re-runs idempotently.
    CONSTRAINT one_transition_per_day UNIQUE (loan_id, as_of)
);

CREATE TRIGGER delinquency_events_are_append_only
    BEFORE UPDATE OR DELETE ON lending.delinquency_events
    FOR EACH ROW EXECUTE FUNCTION lending.reject_mutation();

-- ---------------------------------------------------------------------------
-- Outbox. Lending's own, per the doctrine each emitting module owns one.
-- ---------------------------------------------------------------------------
CREATE TABLE lending.outbox_events (
    id           BIGSERIAL   PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    event_type   TEXT        NOT NULL,
    aggregate_id TEXT        NOT NULL,
    epoch        BIGINT      NOT NULL DEFAULT 1 CHECK (epoch > 0),
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX lending_outbox_events_pending
    ON lending.outbox_events (id)
    WHERE published_at IS NULL;

-- ---------------------------------------------------------------------------
-- Row-level security: the platform pattern, verbatim (ADR 0007). The worker
-- runs the cross-tenant jobs; the relay publishes the outbox — policies, never
-- BYPASSRLS.
-- ---------------------------------------------------------------------------
ALTER TABLE lending.approval_tiers        ENABLE ROW LEVEL SECURITY;
ALTER TABLE lending.loan_applications     ENABLE ROW LEVEL SECURITY;
ALTER TABLE lending.loan_approvals        ENABLE ROW LEVEL SECURITY;
ALTER TABLE lending.loans                 ENABLE ROW LEVEL SECURITY;
ALTER TABLE lending.loan_schedule         ENABLE ROW LEVEL SECURITY;
ALTER TABLE lending.repayments            ENABLE ROW LEVEL SECURITY;
ALTER TABLE lending.repayment_allocations ENABLE ROW LEVEL SECURITY;
ALTER TABLE lending.delinquency_events    ENABLE ROW LEVEL SECURITY;
ALTER TABLE lending.outbox_events         ENABLE ROW LEVEL SECURITY;

ALTER TABLE lending.approval_tiers        FORCE ROW LEVEL SECURITY;
ALTER TABLE lending.loan_applications     FORCE ROW LEVEL SECURITY;
ALTER TABLE lending.loan_approvals        FORCE ROW LEVEL SECURITY;
ALTER TABLE lending.loans                 FORCE ROW LEVEL SECURITY;
ALTER TABLE lending.loan_schedule         FORCE ROW LEVEL SECURITY;
ALTER TABLE lending.repayments            FORCE ROW LEVEL SECURITY;
ALTER TABLE lending.repayment_allocations FORCE ROW LEVEL SECURITY;
ALTER TABLE lending.delinquency_events    FORCE ROW LEVEL SECURITY;
ALTER TABLE lending.outbox_events         FORCE ROW LEVEL SECURITY;

CREATE POLICY approval_tiers_tenant_isolation ON lending.approval_tiers
    USING (tenant_id = lending.current_tenant()) WITH CHECK (tenant_id = lending.current_tenant());
CREATE POLICY loan_applications_tenant_isolation ON lending.loan_applications
    USING (tenant_id = lending.current_tenant()) WITH CHECK (tenant_id = lending.current_tenant());
CREATE POLICY loan_approvals_tenant_isolation ON lending.loan_approvals
    USING (tenant_id = lending.current_tenant()) WITH CHECK (tenant_id = lending.current_tenant());
CREATE POLICY loans_tenant_isolation ON lending.loans
    USING (tenant_id = lending.current_tenant()) WITH CHECK (tenant_id = lending.current_tenant());
CREATE POLICY loan_schedule_tenant_isolation ON lending.loan_schedule
    USING (tenant_id = lending.current_tenant()) WITH CHECK (tenant_id = lending.current_tenant());
CREATE POLICY repayments_tenant_isolation ON lending.repayments
    USING (tenant_id = lending.current_tenant()) WITH CHECK (tenant_id = lending.current_tenant());
CREATE POLICY repayment_allocations_tenant_isolation ON lending.repayment_allocations
    USING (tenant_id = lending.current_tenant()) WITH CHECK (tenant_id = lending.current_tenant());
CREATE POLICY delinquency_events_tenant_isolation ON lending.delinquency_events
    USING (tenant_id = lending.current_tenant()) WITH CHECK (tenant_id = lending.current_tenant());
CREATE POLICY outbox_events_tenant_isolation ON lending.outbox_events
    USING (tenant_id = lending.current_tenant()) WITH CHECK (tenant_id = lending.current_tenant());

-- The worker: accrual, delinquency and convergence jobs scan every tenant.
GRANT USAGE ON SCHEMA lending TO core_worker;
GRANT SELECT, UPDATE ON lending.loan_applications TO core_worker;
GRANT SELECT, UPDATE ON lending.loans TO core_worker;
GRANT SELECT, UPDATE ON lending.loan_schedule TO core_worker;
GRANT SELECT, UPDATE ON lending.repayments TO core_worker;
GRANT SELECT, INSERT ON lending.repayment_allocations TO core_worker;
GRANT SELECT, INSERT ON lending.delinquency_events TO core_worker;
GRANT SELECT, INSERT ON lending.outbox_events TO core_worker;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA lending TO core_worker;

CREATE POLICY loan_applications_worker_access ON lending.loan_applications
    USING (current_user = 'core_worker') WITH CHECK (current_user = 'core_worker');
CREATE POLICY loans_worker_access ON lending.loans
    USING (current_user = 'core_worker') WITH CHECK (current_user = 'core_worker');
CREATE POLICY loan_schedule_worker_access ON lending.loan_schedule
    USING (current_user = 'core_worker') WITH CHECK (current_user = 'core_worker');
CREATE POLICY repayments_worker_access ON lending.repayments
    USING (current_user = 'core_worker') WITH CHECK (current_user = 'core_worker');
CREATE POLICY repayment_allocations_worker_access ON lending.repayment_allocations
    USING (current_user = 'core_worker') WITH CHECK (current_user = 'core_worker');
CREATE POLICY delinquency_events_worker_access ON lending.delinquency_events
    USING (current_user = 'core_worker') WITH CHECK (current_user = 'core_worker');
CREATE POLICY outbox_events_worker_access ON lending.outbox_events
    USING (current_user = 'core_worker') WITH CHECK (current_user = 'core_worker');

-- The relay: lending's outbox joins its poll. Narrow, as everywhere.
GRANT USAGE ON SCHEMA lending TO core_relay;
GRANT SELECT, UPDATE ON lending.outbox_events TO core_relay;

CREATE POLICY outbox_events_relay_access ON lending.outbox_events
    USING (current_user = 'core_relay') WITH CHECK (current_user = 'core_relay');
