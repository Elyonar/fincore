-- Orchestration's tables (services/core/docs/data-model.md).
--
-- The correctness that matters lives here rather than in application code:
-- terminal states that cannot move, attempt history that cannot be edited,
-- approvals that cannot be replayed, and tenant isolation that holds even when
-- a query forgets to scope itself.

-- ---------------------------------------------------------------------------
-- Tenant context, identical in form to the ledger's.
--
-- Returns NULL when unset, so every policy below matches nothing rather than
-- everything. A backstop that opens up when unconfigured is not a backstop.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION orchestration.current_tenant() RETURNS UUID
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
-- Tills. A deliberate v1 simplification: a till is a ledger account and not a
-- customer, and there is no Branch domain yet (design.md).
-- ---------------------------------------------------------------------------
CREATE TABLE orchestration.tills (
    id                UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id         UUID        NOT NULL,
    branch_code       TEXT        NOT NULL,
    ledger_account_id UUID        NOT NULL,
    currency          CHAR(3)     NOT NULL,
    assigned_to       TEXT,
    status            TEXT        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
    opened_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at         TIMESTAMPTZ,
    PRIMARY KEY (id),
    -- The composite key exists so that other tables reference (tenant_id, id)
    -- and a cross-tenant reference becomes structurally impossible.
    UNIQUE (tenant_id, id)
);

-- ---------------------------------------------------------------------------
-- Approvals. Maker-checker for business reversals.
-- ---------------------------------------------------------------------------
CREATE TABLE orchestration.approvals (
    id             UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id      UUID        NOT NULL,
    action         TEXT        NOT NULL CHECK (action IN ('REVERSAL')),
    target_saga_id UUID        NOT NULL,
    amount_minor   BIGINT      NOT NULL CHECK (amount_minor > 0),
    status         TEXT        NOT NULL DEFAULT 'PENDING'
                               CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CONSUMED')),
    made_by        TEXT        NOT NULL,
    checked_by     TEXT,
    made_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    checked_at     TIMESTAMPTZ,
    consumed_at    TIMESTAMPTZ,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    -- Maker must not be checker. Enforced here rather than in code because an
    -- approval with one person on both sides is not maker-checker at all, and
    -- the whole control is worthless if it can be bypassed by a bug.
    CONSTRAINT checker_differs_from_maker CHECK (checked_by IS NULL OR checked_by <> made_by),
    -- An approved approval has a checker; a consumed one has been spent.
    CONSTRAINT approved_has_checker CHECK (status <> 'APPROVED' OR checked_by IS NOT NULL),
    CONSTRAINT consumed_has_timestamp CHECK (status <> 'CONSUMED' OR consumed_at IS NOT NULL)
);

-- ---------------------------------------------------------------------------
-- Sagas. The unit of work and the audit record of an intent.
-- ---------------------------------------------------------------------------
CREATE TABLE orchestration.sagas (
    id                       UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id                UUID        NOT NULL,
    type                     TEXT        NOT NULL
                                         CHECK (type IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL', 'REVERSAL')),
    state                    TEXT        NOT NULL DEFAULT 'RECEIVED'
                                         CHECK (state IN ('RECEIVED', 'POSTING', 'COMPLETED',
                                                          'FAILED', 'PENDING_RESOLUTION')),
    channel_idempotency_key  TEXT        NOT NULL CHECK (length(channel_idempotency_key) <= 200),
    request_fingerprint      TEXT        NOT NULL,
    subject_customer_id      UUID,
    product_code             TEXT,
    product_version          INT,
    decision                 JSONB,
    amount_minor             BIGINT      NOT NULL CHECK (amount_minor > 0),
    fee_minor                BIGINT      NOT NULL DEFAULT 0 CHECK (fee_minor >= 0),
    currency                 CHAR(3)     NOT NULL,
    ledger_transaction_id    UUID,
    reverses_saga_id         UUID,
    approval_id              UUID,
    till_id                  UUID,
    attempts                 INT         NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_by               TEXT,
    claim_expires_at         TIMESTAMPTZ,
    last_error               TEXT,
    initiated_by             TEXT        NOT NULL,
    executed_by              TEXT        NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    terminal_at              TIMESTAMPTZ,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),

    -- The arbiter of duplicate submissions. The index decides races, not
    -- application code — the same discipline the ledger applies to postings.
    CONSTRAINT sagas_idempotent UNIQUE (tenant_id, channel_idempotency_key),

    -- Composite, so a saga can only ever reference its own tenant's rows.
    FOREIGN KEY (tenant_id, reverses_saga_id) REFERENCES orchestration.sagas (tenant_id, id),
    FOREIGN KEY (tenant_id, approval_id)      REFERENCES orchestration.approvals (tenant_id, id),
    FOREIGN KEY (tenant_id, till_id)          REFERENCES orchestration.tills (tenant_id, id),

    -- A reversal targets something and carries an approval; nothing else does.
    CONSTRAINT reversal_shape CHECK (
        (type = 'REVERSAL' AND reverses_saga_id IS NOT NULL AND approval_id IS NOT NULL)
        OR (type <> 'REVERSAL' AND reverses_saga_id IS NULL AND approval_id IS NULL)),

    -- Terminal states carry their timestamp, and only terminal states do.
    CONSTRAINT terminal_states_are_stamped CHECK (
        (state IN ('COMPLETED', 'FAILED') AND terminal_at IS NOT NULL)
        OR (state NOT IN ('COMPLETED', 'FAILED') AND terminal_at IS NULL)),

    -- A completed saga knows what it posted. A failed one posted nothing.
    CONSTRAINT completed_has_a_ledger_transaction CHECK (
        state <> 'COMPLETED' OR ledger_transaction_id IS NOT NULL),
    CONSTRAINT failed_has_no_ledger_transaction CHECK (
        state <> 'FAILED' OR ledger_transaction_id IS NULL)
);

-- The claim query: non-terminal work whose lease has expired and whose next
-- attempt is due. Partial, so it stays proportional to outstanding work rather
-- than to history.
CREATE INDEX sagas_claimable
    ON orchestration.sagas (next_attempt_at)
    WHERE state IN ('RECEIVED', 'POSTING');

-- ---------------------------------------------------------------------------
-- Attempt history. Append-only: this is the record of every outbound attempt
-- including the unknowns, which is exactly what an incident review needs and
-- exactly what a well-meaning cleanup would otherwise tidy away.
-- ---------------------------------------------------------------------------
CREATE TABLE orchestration.saga_attempts (
    id          BIGSERIAL   PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    saga_id     UUID        NOT NULL,
    attempt_no  INT         NOT NULL CHECK (attempt_no > 0),
    outcome     TEXT        NOT NULL CHECK (outcome IN ('SUCCESS', 'DEFINITE_FAILURE', 'UNKNOWN')),
    detail      TEXT,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    FOREIGN KEY (tenant_id, saga_id) REFERENCES orchestration.sagas (tenant_id, id),
    UNIQUE (saga_id, attempt_no)
);

-- ---------------------------------------------------------------------------
-- Limit reservations. Reservations rather than checks, because a check-then-post
-- races: two concurrent transfers each observe the limit unbreached.
-- ---------------------------------------------------------------------------
CREATE TABLE orchestration.limit_reservations (
    id           UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id    UUID        NOT NULL,
    saga_id      UUID        NOT NULL,
    subject_id   UUID        NOT NULL,
    limit_type   TEXT        NOT NULL CHECK (limit_type IN ('PER_TXN', 'DAILY')),
    window_key   TEXT        NOT NULL,
    amount_minor BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency     CHAR(3)     NOT NULL,
    status       TEXT        NOT NULL DEFAULT 'RESERVED'
                             CHECK (status IN ('RESERVED', 'CONSUMED', 'RELEASED', 'EXPIRED')),
    expires_at   TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at  TIMESTAMPTZ,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, saga_id) REFERENCES orchestration.sagas (tenant_id, id),
    -- One reservation per saga in v1. Multi-reservation arrives with rails.
    CONSTRAINT one_reservation_per_saga UNIQUE (saga_id),
    CONSTRAINT resolved_states_are_stamped CHECK (
        (status = 'RESERVED' AND resolved_at IS NULL)
        OR (status <> 'RESERVED' AND resolved_at IS NOT NULL))
);

CREATE INDEX limit_reservations_window
    ON orchestration.limit_reservations (tenant_id, subject_id, window_key)
    WHERE status IN ('RESERVED', 'CONSUMED');

-- ---------------------------------------------------------------------------
-- Ops cases. Raised when an unknown outcome outlives its escalation bound.
--
-- Note what a case cannot record: an operator's opinion about what happened.
-- `resolution` is written by the system once the Ledger answers, never by a
-- human — the moment an operator can assert an outcome, the outcome protocol's
-- central guarantee becomes advisory (api.md).
-- ---------------------------------------------------------------------------
CREATE TABLE orchestration.ops_cases (
    id          UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL,
    saga_id     UUID        NOT NULL,
    kind        TEXT        NOT NULL CHECK (kind IN ('UNRESOLVED_OUTCOME')),
    status      TEXT        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED')),
    resolution  TEXT        CHECK (resolution IN ('POSTED', 'NOT_POSTED')),
    resolved_by TEXT,
    opened_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, saga_id) REFERENCES orchestration.sagas (tenant_id, id),
    CONSTRAINT resolved_cases_have_a_resolution CHECK (
        status <> 'RESOLVED' OR (resolution IS NOT NULL AND resolved_at IS NOT NULL))
);

CREATE INDEX ops_cases_open ON orchestration.ops_cases (tenant_id) WHERE status = 'OPEN';

-- ---------------------------------------------------------------------------
-- Outbox. Written in the same transaction as the state change, so an event
-- exists if and only if the change committed (ADR 0008).
-- ---------------------------------------------------------------------------
CREATE TABLE orchestration.outbox_events (
    id           BIGSERIAL   PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    event_type   TEXT        NOT NULL,
    aggregate_id TEXT        NOT NULL,
    epoch        BIGINT      NOT NULL DEFAULT 1 CHECK (epoch > 0),
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

-- Partial, so the relay's poll stays O(pending) rather than O(history).
CREATE INDEX outbox_events_pending
    ON orchestration.outbox_events (id)
    WHERE published_at IS NULL;

-- ---------------------------------------------------------------------------
-- Tamper evidence, by trigger — so it does not depend on application code being
-- correct or on a maintenance script being careful.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION orchestration.reject_mutation() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'append-only: % on % is not permitted', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER saga_attempts_are_append_only
    BEFORE UPDATE OR DELETE ON orchestration.saga_attempts
    FOR EACH ROW EXECUTE FUNCTION orchestration.reject_mutation();

-- Terminal states are terminal. Invariant 5 (testing.md), enforced where it
-- cannot be forgotten: a COMPLETED saga that could be reopened would let a
-- reconciliation disagree with the Ledger and be "fixed" by editing the record.
CREATE OR REPLACE FUNCTION orchestration.reject_terminal_transition() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.terminal_at IS NOT NULL
       AND (NEW.state IS DISTINCT FROM OLD.state
            OR NEW.terminal_at IS DISTINCT FROM OLD.terminal_at
            OR NEW.ledger_transaction_id IS DISTINCT FROM OLD.ledger_transaction_id) THEN
        RAISE EXCEPTION
            'saga % is terminal (%); its state and outcome cannot change', OLD.id, OLD.state
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER sagas_terminal_states_are_terminal
    BEFORE UPDATE ON orchestration.sagas
    FOR EACH ROW EXECUTE FUNCTION orchestration.reject_terminal_transition();

-- An approval is single-use. Without this, one approval could authorize a
-- second reversal of the same transaction — a double credit with an audit trail
-- that looks impeccable.
CREATE OR REPLACE FUNCTION orchestration.reject_approval_reuse() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'CONSUMED' AND NEW.status IS DISTINCT FROM 'CONSUMED' THEN
        RAISE EXCEPTION 'approval % has been spent and cannot be reused', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER approvals_are_single_use
    BEFORE UPDATE ON orchestration.approvals
    FOR EACH ROW EXECUTE FUNCTION orchestration.reject_approval_reuse();

-- ---------------------------------------------------------------------------
-- Row-level security. FORCEd, because PostgreSQL exempts a table's owner
-- otherwise — and the owner runs migrations here.
-- ---------------------------------------------------------------------------
ALTER TABLE orchestration.sagas              ENABLE ROW LEVEL SECURITY;
ALTER TABLE orchestration.saga_attempts      ENABLE ROW LEVEL SECURITY;
ALTER TABLE orchestration.limit_reservations ENABLE ROW LEVEL SECURITY;
ALTER TABLE orchestration.approvals          ENABLE ROW LEVEL SECURITY;
ALTER TABLE orchestration.tills              ENABLE ROW LEVEL SECURITY;
ALTER TABLE orchestration.ops_cases          ENABLE ROW LEVEL SECURITY;
ALTER TABLE orchestration.outbox_events      ENABLE ROW LEVEL SECURITY;

ALTER TABLE orchestration.sagas              FORCE ROW LEVEL SECURITY;
ALTER TABLE orchestration.saga_attempts      FORCE ROW LEVEL SECURITY;
ALTER TABLE orchestration.limit_reservations FORCE ROW LEVEL SECURITY;
ALTER TABLE orchestration.approvals          FORCE ROW LEVEL SECURITY;
ALTER TABLE orchestration.tills              FORCE ROW LEVEL SECURITY;
ALTER TABLE orchestration.ops_cases          FORCE ROW LEVEL SECURITY;
ALTER TABLE orchestration.outbox_events      FORCE ROW LEVEL SECURITY;

CREATE POLICY sagas_tenant_isolation ON orchestration.sagas
    USING (tenant_id = orchestration.current_tenant())
    WITH CHECK (tenant_id = orchestration.current_tenant());
CREATE POLICY saga_attempts_tenant_isolation ON orchestration.saga_attempts
    USING (tenant_id = orchestration.current_tenant())
    WITH CHECK (tenant_id = orchestration.current_tenant());
CREATE POLICY limit_reservations_tenant_isolation ON orchestration.limit_reservations
    USING (tenant_id = orchestration.current_tenant())
    WITH CHECK (tenant_id = orchestration.current_tenant());
CREATE POLICY approvals_tenant_isolation ON orchestration.approvals
    USING (tenant_id = orchestration.current_tenant())
    WITH CHECK (tenant_id = orchestration.current_tenant());
CREATE POLICY tills_tenant_isolation ON orchestration.tills
    USING (tenant_id = orchestration.current_tenant())
    WITH CHECK (tenant_id = orchestration.current_tenant());
CREATE POLICY ops_cases_tenant_isolation ON orchestration.ops_cases
    USING (tenant_id = orchestration.current_tenant())
    WITH CHECK (tenant_id = orchestration.current_tenant());
CREATE POLICY outbox_events_tenant_isolation ON orchestration.outbox_events
    USING (tenant_id = orchestration.current_tenant())
    WITH CHECK (tenant_id = orchestration.current_tenant());

-- The relay reads every module's outbox and marks rows published. Narrow by
-- design: it is delivery infrastructure, not a module, and it has no business
-- reading a saga.
GRANT USAGE ON SCHEMA orchestration TO core_relay;
GRANT SELECT, UPDATE ON orchestration.outbox_events TO core_relay;

-- The relay has no tenant context — it publishes across every tenant — so the
-- tenant policy above would hide the entire table from it. A second permissive
-- policy admits exactly that role and nothing else.
--
-- This is deliberately a *policy* rather than BYPASSRLS on the role: BYPASSRLS
-- would exempt the relay from every table's policies at once, including sagas,
-- and a delivery component has no business reading a saga.
CREATE POLICY outbox_events_relay_access ON orchestration.outbox_events
    USING (current_user = 'core_relay')
    WITH CHECK (current_user = 'core_relay');
