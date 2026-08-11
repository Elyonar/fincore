-- =============================================================================================
-- orchestration — the schema, in one migration.
--
-- Collapsed from 10 migrations (V10–V9) before the first release. Every one of them had
-- been applied to every database that exists, so nothing is lost by folding them: this file is a
-- dump of exactly the schema they produced, taken from a database they built. What goes is the
-- archaeology — a column added in V4 and widened in V7 reads here as the column it ended up being.
--
-- Legitimate only because there is nothing to preserve compatibility with yet. After the first
-- release the rule inverts and migrations become append-only for good: a deployed database cannot
-- be re-baselined without being rebuilt, and rebuilding a bank's database is not a migration.
-- =============================================================================================

--
--

--
-- Name: orchestration; Type: SCHEMA; Schema: -; Owner: -
--

--
--

--
-- Name: orchestration; Type: SCHEMA; Schema: -; Owner: -
--

-- Flyway creates the schema from its own `schemas` setting; kept so the file also stands
-- alone if it is ever applied by hand.
CREATE SCHEMA IF NOT EXISTS orchestration;

--
-- Name: current_tenant(); Type: FUNCTION; Schema: orchestration; Owner: -
--

CREATE FUNCTION orchestration.current_tenant() RETURNS uuid
    LANGUAGE plpgsql STABLE
    AS $$
DECLARE
    raw TEXT := current_setting('app.tenant_id', true);
BEGIN
    IF raw IS NULL OR raw = '' THEN
        RETURN NULL;
    END IF;
    RETURN raw::UUID;
END;
$$;

--
-- Name: reject_approval_reuse(); Type: FUNCTION; Schema: orchestration; Owner: -
--

CREATE FUNCTION orchestration.reject_approval_reuse() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.status = 'CONSUMED' AND NEW.status IS DISTINCT FROM 'CONSUMED' THEN
        RAISE EXCEPTION 'approval % has been spent and cannot be reused', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

--
-- Name: reject_mutation(); Type: FUNCTION; Schema: orchestration; Owner: -
--

CREATE FUNCTION orchestration.reject_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'append-only: % on % is not permitted', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$;

--
-- Name: reject_terminal_transition(); Type: FUNCTION; Schema: orchestration; Owner: -
--

CREATE FUNCTION orchestration.reject_terminal_transition() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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

--
-- Name: approvals; Type: TABLE; Schema: orchestration; Owner: -
--

CREATE TABLE orchestration.approvals (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    action text NOT NULL,
    target_saga_id uuid NOT NULL,
    amount_minor bigint NOT NULL,
    status text DEFAULT 'PENDING'::text NOT NULL,
    made_by text NOT NULL,
    checked_by text,
    made_at timestamp with time zone DEFAULT now() NOT NULL,
    checked_at timestamp with time zone,
    consumed_at timestamp with time zone,
    made_in_unit text,
    checked_in_unit text,
    CONSTRAINT approvals_action_check CHECK ((action = 'REVERSAL'::text)),
    CONSTRAINT approvals_amount_minor_check CHECK ((amount_minor > 0)),
    CONSTRAINT approvals_status_check CHECK ((status = ANY (ARRAY['PENDING'::text, 'APPROVED'::text, 'REJECTED'::text, 'CONSUMED'::text]))),
    CONSTRAINT approved_has_checker CHECK (((status <> 'APPROVED'::text) OR (checked_by IS NOT NULL))),
    CONSTRAINT checker_differs_from_maker CHECK (((checked_by IS NULL) OR (checked_by <> made_by))),
    CONSTRAINT consumed_has_timestamp CHECK (((status <> 'CONSUMED'::text) OR (consumed_at IS NOT NULL)))
);

ALTER TABLE ONLY orchestration.approvals FORCE ROW LEVEL SECURITY;

--
-- Name: internal_accounts; Type: TABLE; Schema: orchestration; Owner: -
--

CREATE TABLE orchestration.internal_accounts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    ledger_account_id uuid NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    purpose text NOT NULL,
    currency character(3) NOT NULL,
    status text DEFAULT 'ACTIVE'::text NOT NULL,
    opened_at timestamp with time zone DEFAULT now() NOT NULL,
    opened_by text NOT NULL,
    closed_at timestamp with time zone,
    closed_by text,
    CONSTRAINT internal_accounts_purpose_check CHECK ((purpose = ANY (ARRAY['TILL'::text, 'VAULT'::text, 'FEE_INCOME'::text, 'INTEREST_INCOME'::text, 'PENALTY_INCOME'::text, 'LOAN_FUNDING'::text, 'SUSPENSE'::text, 'SETTLEMENT'::text, 'OTHER'::text]))),
    CONSTRAINT internal_accounts_status_check CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'CLOSED'::text])))
);

ALTER TABLE ONLY orchestration.internal_accounts FORCE ROW LEVEL SECURITY;

--
-- Name: limit_reservations; Type: TABLE; Schema: orchestration; Owner: -
--

CREATE TABLE orchestration.limit_reservations (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    saga_id uuid NOT NULL,
    subject_id uuid NOT NULL,
    limit_type text NOT NULL,
    window_key text NOT NULL,
    amount_minor bigint NOT NULL,
    currency character(3) NOT NULL,
    status text DEFAULT 'RESERVED'::text NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    resolved_at timestamp with time zone,
    CONSTRAINT limit_reservations_amount_minor_check CHECK ((amount_minor > 0)),
    CONSTRAINT limit_reservations_limit_type_check CHECK ((limit_type = ANY (ARRAY['PER_TXN'::text, 'DAILY'::text]))),
    CONSTRAINT limit_reservations_status_check CHECK ((status = ANY (ARRAY['RESERVED'::text, 'CONSUMED'::text, 'RELEASED'::text, 'EXPIRED'::text]))),
    CONSTRAINT resolved_states_are_stamped CHECK ((((status = 'RESERVED'::text) AND (resolved_at IS NULL)) OR ((status <> 'RESERVED'::text) AND (resolved_at IS NOT NULL))))
);

ALTER TABLE ONLY orchestration.limit_reservations FORCE ROW LEVEL SECURITY;

--
-- Name: ops_cases; Type: TABLE; Schema: orchestration; Owner: -
--

CREATE TABLE orchestration.ops_cases (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    saga_id uuid NOT NULL,
    kind text NOT NULL,
    status text DEFAULT 'OPEN'::text NOT NULL,
    resolution text,
    resolved_by text,
    opened_at timestamp with time zone DEFAULT now() NOT NULL,
    resolved_at timestamp with time zone,
    CONSTRAINT ops_cases_kind_check CHECK ((kind = ANY (ARRAY['UNRESOLVED_OUTCOME'::text, 'RECONCILIATION_MISMATCH'::text]))),
    CONSTRAINT ops_cases_resolution_check CHECK ((resolution = ANY (ARRAY['POSTED'::text, 'NOT_POSTED'::text]))),
    CONSTRAINT ops_cases_status_check CHECK ((status = ANY (ARRAY['OPEN'::text, 'RESOLVED'::text]))),
    CONSTRAINT resolved_cases_have_a_resolution CHECK (((status <> 'RESOLVED'::text) OR ((resolution IS NOT NULL) AND (resolved_at IS NOT NULL))))
);

ALTER TABLE ONLY orchestration.ops_cases FORCE ROW LEVEL SECURITY;

--
-- Name: outbox_events; Type: TABLE; Schema: orchestration; Owner: -
--

CREATE TABLE orchestration.outbox_events (
    id bigint NOT NULL,
    tenant_id uuid NOT NULL,
    event_type text NOT NULL,
    aggregate_id text NOT NULL,
    epoch bigint DEFAULT 1 NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    published_at timestamp with time zone,
    CONSTRAINT outbox_events_epoch_check CHECK ((epoch > 0))
);

ALTER TABLE ONLY orchestration.outbox_events FORCE ROW LEVEL SECURITY;

--
-- Name: outbox_events_id_seq; Type: SEQUENCE; Schema: orchestration; Owner: -
--

CREATE SEQUENCE orchestration.outbox_events_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: outbox_events_id_seq; Type: SEQUENCE OWNED BY; Schema: orchestration; Owner: -
--

ALTER SEQUENCE orchestration.outbox_events_id_seq OWNED BY orchestration.outbox_events.id;

--
-- Name: reconciliation_findings; Type: TABLE; Schema: orchestration; Owner: -
--

CREATE TABLE orchestration.reconciliation_findings (
    id bigint NOT NULL,
    tenant_id uuid NOT NULL,
    saga_id uuid NOT NULL,
    kind text NOT NULL,
    detail text NOT NULL,
    found_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT reconciliation_findings_kind_check CHECK ((kind = ANY (ARRAY['LEDGER_MISSING'::text, 'AMOUNT_MISMATCH'::text])))
);

ALTER TABLE ONLY orchestration.reconciliation_findings FORCE ROW LEVEL SECURITY;

--
-- Name: reconciliation_findings_id_seq; Type: SEQUENCE; Schema: orchestration; Owner: -
--

CREATE SEQUENCE orchestration.reconciliation_findings_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: reconciliation_findings_id_seq; Type: SEQUENCE OWNED BY; Schema: orchestration; Owner: -
--

ALTER SEQUENCE orchestration.reconciliation_findings_id_seq OWNED BY orchestration.reconciliation_findings.id;

--
-- Name: saga_attempts; Type: TABLE; Schema: orchestration; Owner: -
--

CREATE TABLE orchestration.saga_attempts (
    id bigint NOT NULL,
    tenant_id uuid NOT NULL,
    saga_id uuid NOT NULL,
    attempt_no integer NOT NULL,
    outcome text NOT NULL,
    detail text,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    finished_at timestamp with time zone,
    CONSTRAINT saga_attempts_attempt_no_check CHECK ((attempt_no > 0)),
    CONSTRAINT saga_attempts_outcome_check CHECK ((outcome = ANY (ARRAY['SUCCESS'::text, 'DEFINITE_FAILURE'::text, 'UNKNOWN'::text])))
);

ALTER TABLE ONLY orchestration.saga_attempts FORCE ROW LEVEL SECURITY;

--
-- Name: saga_attempts_id_seq; Type: SEQUENCE; Schema: orchestration; Owner: -
--

CREATE SEQUENCE orchestration.saga_attempts_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: saga_attempts_id_seq; Type: SEQUENCE OWNED BY; Schema: orchestration; Owner: -
--

ALTER SEQUENCE orchestration.saga_attempts_id_seq OWNED BY orchestration.saga_attempts.id;

--
-- Name: transaction_reference_seq; Type: SEQUENCE; Schema: orchestration; Owner: -
--

CREATE SEQUENCE orchestration.transaction_reference_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: transaction_reference(); Type: FUNCTION; Schema: orchestration; Owner: -
--
-- Customer-facing reference: TXN-YYYYMMDD-NNNNN. Zero-padded to five digits for
-- teller legibility, but the width GROWS past 99,999 rather than overflowing —
-- to_char's 'FM00000' template renders 100000 as '#####', which would collide on
-- sagas_reference_per_tenant and refuse every insert on the money path from the
-- 100,000th posting onward. lpad alone is no better: it truncates. Hence the CASE.
--

CREATE FUNCTION orchestration.transaction_reference() RETURNS text
    LANGUAGE sql
    AS $$
    SELECT 'TXN-' || to_char(now(), 'YYYYMMDD') || '-'
        || CASE WHEN n < 100000 THEN lpad(n::text, 5, '0') ELSE n::text END
    FROM (SELECT nextval('orchestration.transaction_reference_seq') AS n) numbered;
$$;

--
-- Name: sagas; Type: TABLE; Schema: orchestration; Owner: -
--

CREATE TABLE orchestration.sagas (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    type text NOT NULL,
    state text DEFAULT 'RECEIVED'::text NOT NULL,
    channel_idempotency_key text NOT NULL,
    request_fingerprint text NOT NULL,
    subject_customer_id uuid,
    product_code text,
    product_version integer,
    decision jsonb,
    amount_minor bigint NOT NULL,
    fee_minor bigint DEFAULT 0 NOT NULL,
    currency character(3) NOT NULL,
    ledger_transaction_id uuid,
    reverses_saga_id uuid,
    approval_id uuid,
    till_id uuid,
    attempts integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone DEFAULT now() NOT NULL,
    claimed_by text,
    claim_expires_at timestamp with time zone,
    last_error text,
    initiated_by text NOT NULL,
    executed_by text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    terminal_at timestamp with time zone,
    from_account_id uuid,
    to_account_id uuid,
    fee_account_id uuid,
    reference text DEFAULT orchestration.transaction_reference() NOT NULL,
    CONSTRAINT completed_has_a_ledger_transaction CHECK (((state <> 'COMPLETED'::text) OR (ledger_transaction_id IS NOT NULL))),
    CONSTRAINT failed_has_no_ledger_transaction CHECK (((state <> 'FAILED'::text) OR (ledger_transaction_id IS NULL))),
    CONSTRAINT reversal_shape CHECK ((((type = 'REVERSAL'::text) AND (reverses_saga_id IS NOT NULL) AND (approval_id IS NOT NULL)) OR ((type <> 'REVERSAL'::text) AND (reverses_saga_id IS NULL) AND (approval_id IS NULL)))),
    CONSTRAINT sagas_amount_minor_check CHECK ((amount_minor > 0)),
    CONSTRAINT sagas_attempts_check CHECK ((attempts >= 0)),
    CONSTRAINT sagas_channel_idempotency_key_check CHECK ((length(channel_idempotency_key) <= 200)),
    CONSTRAINT sagas_fee_minor_check CHECK ((fee_minor >= 0)),
    CONSTRAINT sagas_state_check CHECK ((state = ANY (ARRAY['RECEIVED'::text, 'POSTING'::text, 'COMPLETED'::text, 'FAILED'::text, 'PENDING_RESOLUTION'::text]))),
    CONSTRAINT sagas_type_check CHECK ((type = ANY (ARRAY['TRANSFER'::text, 'DEPOSIT'::text, 'WITHDRAWAL'::text, 'REVERSAL'::text, 'DISBURSEMENT'::text, 'REPAYMENT'::text, 'RECOGNITION'::text]))),
    CONSTRAINT terminal_states_are_stamped CHECK ((((state = ANY (ARRAY['COMPLETED'::text, 'FAILED'::text])) AND (terminal_at IS NOT NULL)) OR ((state <> ALL (ARRAY['COMPLETED'::text, 'FAILED'::text])) AND (terminal_at IS NULL))))
);

ALTER TABLE ONLY orchestration.sagas FORCE ROW LEVEL SECURITY;

--
-- Name: tills; Type: TABLE; Schema: orchestration; Owner: -
--

CREATE TABLE orchestration.tills (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid NOT NULL,
    branch_code text NOT NULL,
    ledger_account_id uuid NOT NULL,
    currency character(3) NOT NULL,
    assigned_to text,
    status text DEFAULT 'OPEN'::text NOT NULL,
    opened_at timestamp with time zone DEFAULT now() NOT NULL,
    closed_at timestamp with time zone,
    unit_id uuid,
    internal_account_id uuid,
    CONSTRAINT tills_status_check CHECK ((status = ANY (ARRAY['OPEN'::text, 'CLOSED'::text])))
);

ALTER TABLE ONLY orchestration.tills FORCE ROW LEVEL SECURITY;

--
-- Name: outbox_events id; Type: DEFAULT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.outbox_events ALTER COLUMN id SET DEFAULT nextval('orchestration.outbox_events_id_seq'::regclass);

--
-- Name: reconciliation_findings id; Type: DEFAULT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.reconciliation_findings ALTER COLUMN id SET DEFAULT nextval('orchestration.reconciliation_findings_id_seq'::regclass);

--
-- Name: saga_attempts id; Type: DEFAULT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.saga_attempts ALTER COLUMN id SET DEFAULT nextval('orchestration.saga_attempts_id_seq'::regclass);

--
-- Name: approvals approvals_pkey; Type: CONSTRAINT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.approvals
    ADD CONSTRAINT approvals_pkey PRIMARY KEY (id);

--
-- Name: approvals approvals_tenant_id_id_key; Type: CONSTRAINT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.approvals
    ADD CONSTRAINT approvals_tenant_id_id_key UNIQUE (tenant_id, id);

--
-- Name: internal_accounts internal_accounts_pkey; Type: CONSTRAINT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.internal_accounts
    ADD CONSTRAINT internal_accounts_pkey PRIMARY KEY (id);

--
-- Name: internal_accounts internal_accounts_tenant_id_id_key; Type: CONSTRAINT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.internal_accounts
    ADD CONSTRAINT internal_accounts_tenant_id_id_key UNIQUE (tenant_id, id);

--
-- Name: limit_reservations limit_reservations_pkey; Type: CONSTRAINT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.limit_reservations
    ADD CONSTRAINT limit_reservations_pkey PRIMARY KEY (id);

--
-- Name: limit_reservations limit_reservations_tenant_id_id_key; Type: CONSTRAINT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.limit_reservations
    ADD CONSTRAINT limit_reservations_tenant_id_id_key UNIQUE (tenant_id, id);

--
-- Name: sagas money_movements_name_their_accounts; Type: CHECK CONSTRAINT; Schema: orchestration; Owner: -
--

ALTER TABLE orchestration.sagas
    ADD CONSTRAINT money_movements_name_their_accounts CHECK (((type = 'REVERSAL'::text) OR ((from_account_id IS NOT NULL) AND (to_account_id IS NOT NULL)))) NOT VALID;

--
-- Name: reconciliation_findings one_finding_per_saga_per_kind; Type: CONSTRAINT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.reconciliation_findings
    ADD CONSTRAINT one_finding_per_saga_per_kind UNIQUE (saga_id, kind);

--
-- Name: limit_reservations one_reservation_per_saga; Type: CONSTRAINT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.limit_reservations
    ADD CONSTRAINT one_reservation_per_saga UNIQUE (saga_id);

--
-- Name: ops_cases ops_cases_pkey; Type: CONSTRAINT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.ops_cases
    ADD CONSTRAINT ops_cases_pkey PRIMARY KEY (id);

--
-- Name: ops_cases ops_cases_tenant_id_id_key; Type: CONSTRAINT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.ops_cases
    ADD CONSTRAINT ops_cases_tenant_id_id_key UNIQUE (tenant_id, id);

--
-- Name: outbox_events outbox_events_pkey; Type: CONSTRAINT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.outbox_events
    ADD CONSTRAINT outbox_events_pkey PRIMARY KEY (id);

--
-- Name: reconciliation_findings reconciliation_findings_pkey; Type: CONSTRAINT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.reconciliation_findings
    ADD CONSTRAINT reconciliation_findings_pkey PRIMARY KEY (id);

--
-- Name: saga_attempts saga_attempts_pkey; Type: CONSTRAINT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.saga_attempts
    ADD CONSTRAINT saga_attempts_pkey PRIMARY KEY (id);

--
-- Name: saga_attempts saga_attempts_saga_id_attempt_no_key; Type: CONSTRAINT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.saga_attempts
    ADD CONSTRAINT saga_attempts_saga_id_attempt_no_key UNIQUE (saga_id, attempt_no);

--
-- Name: sagas sagas_idempotent; Type: CONSTRAINT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.sagas
    ADD CONSTRAINT sagas_idempotent UNIQUE (tenant_id, channel_idempotency_key);

--
-- Name: sagas sagas_pkey; Type: CONSTRAINT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.sagas
    ADD CONSTRAINT sagas_pkey PRIMARY KEY (id);

--
-- Name: sagas sagas_tenant_id_id_key; Type: CONSTRAINT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.sagas
    ADD CONSTRAINT sagas_tenant_id_id_key UNIQUE (tenant_id, id);

--
-- Name: tills tills_pkey; Type: CONSTRAINT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.tills
    ADD CONSTRAINT tills_pkey PRIMARY KEY (id);

--
-- Name: tills tills_tenant_id_id_key; Type: CONSTRAINT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.tills
    ADD CONSTRAINT tills_tenant_id_id_key UNIQUE (tenant_id, id);

--
-- Name: internal_accounts_by_purpose; Type: INDEX; Schema: orchestration; Owner: -
--

CREATE INDEX internal_accounts_by_purpose ON orchestration.internal_accounts USING btree (tenant_id, purpose) WHERE (status = 'ACTIVE'::text);

--
-- Name: internal_accounts_code_per_tenant; Type: INDEX; Schema: orchestration; Owner: -
--

CREATE UNIQUE INDEX internal_accounts_code_per_tenant ON orchestration.internal_accounts USING btree (tenant_id, lower(code));

--
-- Name: internal_accounts_one_name_per_ledger_account; Type: INDEX; Schema: orchestration; Owner: -
--

CREATE UNIQUE INDEX internal_accounts_one_name_per_ledger_account ON orchestration.internal_accounts USING btree (tenant_id, ledger_account_id);

--
-- Name: limit_reservations_window; Type: INDEX; Schema: orchestration; Owner: -
--

CREATE INDEX limit_reservations_window ON orchestration.limit_reservations USING btree (tenant_id, subject_id, window_key) WHERE (status = ANY (ARRAY['RESERVED'::text, 'CONSUMED'::text]));

--
-- Name: one_open_case_per_saga; Type: INDEX; Schema: orchestration; Owner: -
--

CREATE UNIQUE INDEX one_open_case_per_saga ON orchestration.ops_cases USING btree (saga_id) WHERE (status = 'OPEN'::text);

--
-- Name: ops_cases_open; Type: INDEX; Schema: orchestration; Owner: -
--

CREATE INDEX ops_cases_open ON orchestration.ops_cases USING btree (tenant_id) WHERE (status = 'OPEN'::text);

--
-- Name: outbox_events_pending; Type: INDEX; Schema: orchestration; Owner: -
--

CREATE INDEX outbox_events_pending ON orchestration.outbox_events USING btree (id) WHERE (published_at IS NULL);

--
-- Name: sagas_claimable; Type: INDEX; Schema: orchestration; Owner: -
--

CREATE INDEX sagas_claimable ON orchestration.sagas USING btree (next_attempt_at) WHERE (state = ANY (ARRAY['RECEIVED'::text, 'POSTING'::text]));

--
-- Name: sagas_reference_per_tenant; Type: INDEX; Schema: orchestration; Owner: -
--

CREATE UNIQUE INDEX sagas_reference_per_tenant ON orchestration.sagas USING btree (tenant_id, reference);

--
-- Name: approvals approvals_are_single_use; Type: TRIGGER; Schema: orchestration; Owner: -
--

CREATE TRIGGER approvals_are_single_use BEFORE UPDATE ON orchestration.approvals FOR EACH ROW EXECUTE FUNCTION orchestration.reject_approval_reuse();

--
-- Name: reconciliation_findings reconciliation_findings_are_append_only; Type: TRIGGER; Schema: orchestration; Owner: -
--

CREATE TRIGGER reconciliation_findings_are_append_only BEFORE DELETE OR UPDATE ON orchestration.reconciliation_findings FOR EACH ROW EXECUTE FUNCTION orchestration.reject_mutation();

--
-- Name: saga_attempts saga_attempts_are_append_only; Type: TRIGGER; Schema: orchestration; Owner: -
--

CREATE TRIGGER saga_attempts_are_append_only BEFORE DELETE OR UPDATE ON orchestration.saga_attempts FOR EACH ROW EXECUTE FUNCTION orchestration.reject_mutation();

--
-- Name: sagas sagas_terminal_states_are_terminal; Type: TRIGGER; Schema: orchestration; Owner: -
--

CREATE TRIGGER sagas_terminal_states_are_terminal BEFORE UPDATE ON orchestration.sagas FOR EACH ROW EXECUTE FUNCTION orchestration.reject_terminal_transition();

--
-- Name: limit_reservations limit_reservations_tenant_id_saga_id_fkey; Type: FK CONSTRAINT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.limit_reservations
    ADD CONSTRAINT limit_reservations_tenant_id_saga_id_fkey FOREIGN KEY (tenant_id, saga_id) REFERENCES orchestration.sagas(tenant_id, id);

--
-- Name: ops_cases ops_cases_tenant_id_saga_id_fkey; Type: FK CONSTRAINT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.ops_cases
    ADD CONSTRAINT ops_cases_tenant_id_saga_id_fkey FOREIGN KEY (tenant_id, saga_id) REFERENCES orchestration.sagas(tenant_id, id);

--
-- Name: reconciliation_findings reconciliation_findings_tenant_id_saga_id_fkey; Type: FK CONSTRAINT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.reconciliation_findings
    ADD CONSTRAINT reconciliation_findings_tenant_id_saga_id_fkey FOREIGN KEY (tenant_id, saga_id) REFERENCES orchestration.sagas(tenant_id, id);

--
-- Name: saga_attempts saga_attempts_tenant_id_saga_id_fkey; Type: FK CONSTRAINT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.saga_attempts
    ADD CONSTRAINT saga_attempts_tenant_id_saga_id_fkey FOREIGN KEY (tenant_id, saga_id) REFERENCES orchestration.sagas(tenant_id, id);

--
-- Name: sagas sagas_tenant_id_approval_id_fkey; Type: FK CONSTRAINT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.sagas
    ADD CONSTRAINT sagas_tenant_id_approval_id_fkey FOREIGN KEY (tenant_id, approval_id) REFERENCES orchestration.approvals(tenant_id, id);

--
-- Name: sagas sagas_tenant_id_reverses_saga_id_fkey; Type: FK CONSTRAINT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.sagas
    ADD CONSTRAINT sagas_tenant_id_reverses_saga_id_fkey FOREIGN KEY (tenant_id, reverses_saga_id) REFERENCES orchestration.sagas(tenant_id, id);

--
-- Name: sagas sagas_tenant_id_till_id_fkey; Type: FK CONSTRAINT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.sagas
    ADD CONSTRAINT sagas_tenant_id_till_id_fkey FOREIGN KEY (tenant_id, till_id) REFERENCES orchestration.tills(tenant_id, id);

--
-- Name: tills tills_internal_account_fk; Type: FK CONSTRAINT; Schema: orchestration; Owner: -
--

ALTER TABLE ONLY orchestration.tills
    ADD CONSTRAINT tills_internal_account_fk FOREIGN KEY (tenant_id, internal_account_id) REFERENCES orchestration.internal_accounts(tenant_id, id);

--
-- Name: approvals; Type: ROW SECURITY; Schema: orchestration; Owner: -
--

ALTER TABLE orchestration.approvals ENABLE ROW LEVEL SECURITY;

--
-- Name: approvals approvals_tenant_isolation; Type: POLICY; Schema: orchestration; Owner: -
--

CREATE POLICY approvals_tenant_isolation ON orchestration.approvals USING ((tenant_id = orchestration.current_tenant())) WITH CHECK ((tenant_id = orchestration.current_tenant()));

--
-- Name: internal_accounts; Type: ROW SECURITY; Schema: orchestration; Owner: -
--

ALTER TABLE orchestration.internal_accounts ENABLE ROW LEVEL SECURITY;

--
-- Name: internal_accounts internal_accounts_tenant_isolation; Type: POLICY; Schema: orchestration; Owner: -
--

CREATE POLICY internal_accounts_tenant_isolation ON orchestration.internal_accounts USING ((tenant_id = orchestration.current_tenant())) WITH CHECK ((tenant_id = orchestration.current_tenant()));

--
-- Name: limit_reservations; Type: ROW SECURITY; Schema: orchestration; Owner: -
--

ALTER TABLE orchestration.limit_reservations ENABLE ROW LEVEL SECURITY;

--
-- Name: limit_reservations limit_reservations_tenant_isolation; Type: POLICY; Schema: orchestration; Owner: -
--

CREATE POLICY limit_reservations_tenant_isolation ON orchestration.limit_reservations USING ((tenant_id = orchestration.current_tenant())) WITH CHECK ((tenant_id = orchestration.current_tenant()));

--
-- Name: limit_reservations limit_reservations_worker_access; Type: POLICY; Schema: orchestration; Owner: -
--

CREATE POLICY limit_reservations_worker_access ON orchestration.limit_reservations USING ((CURRENT_USER = 'core_worker'::name)) WITH CHECK ((CURRENT_USER = 'core_worker'::name));

--
-- Name: ops_cases; Type: ROW SECURITY; Schema: orchestration; Owner: -
--

ALTER TABLE orchestration.ops_cases ENABLE ROW LEVEL SECURITY;

--
-- Name: ops_cases ops_cases_tenant_isolation; Type: POLICY; Schema: orchestration; Owner: -
--

CREATE POLICY ops_cases_tenant_isolation ON orchestration.ops_cases USING ((tenant_id = orchestration.current_tenant())) WITH CHECK ((tenant_id = orchestration.current_tenant()));

--
-- Name: ops_cases ops_cases_worker_access; Type: POLICY; Schema: orchestration; Owner: -
--

CREATE POLICY ops_cases_worker_access ON orchestration.ops_cases USING ((CURRENT_USER = 'core_worker'::name)) WITH CHECK ((CURRENT_USER = 'core_worker'::name));

--
-- Name: outbox_events; Type: ROW SECURITY; Schema: orchestration; Owner: -
--

ALTER TABLE orchestration.outbox_events ENABLE ROW LEVEL SECURITY;

--
-- Name: outbox_events outbox_events_relay_access; Type: POLICY; Schema: orchestration; Owner: -
--

CREATE POLICY outbox_events_relay_access ON orchestration.outbox_events USING ((CURRENT_USER = 'core_relay'::name)) WITH CHECK ((CURRENT_USER = 'core_relay'::name));

--
-- Name: outbox_events outbox_events_tenant_isolation; Type: POLICY; Schema: orchestration; Owner: -
--

CREATE POLICY outbox_events_tenant_isolation ON orchestration.outbox_events USING ((tenant_id = orchestration.current_tenant())) WITH CHECK ((tenant_id = orchestration.current_tenant()));

--
-- Name: reconciliation_findings; Type: ROW SECURITY; Schema: orchestration; Owner: -
--

ALTER TABLE orchestration.reconciliation_findings ENABLE ROW LEVEL SECURITY;

--
-- Name: reconciliation_findings reconciliation_findings_tenant_isolation; Type: POLICY; Schema: orchestration; Owner: -
--

CREATE POLICY reconciliation_findings_tenant_isolation ON orchestration.reconciliation_findings USING ((tenant_id = orchestration.current_tenant())) WITH CHECK ((tenant_id = orchestration.current_tenant()));

--
-- Name: reconciliation_findings reconciliation_findings_worker_access; Type: POLICY; Schema: orchestration; Owner: -
--

CREATE POLICY reconciliation_findings_worker_access ON orchestration.reconciliation_findings USING ((CURRENT_USER = 'core_worker'::name)) WITH CHECK ((CURRENT_USER = 'core_worker'::name));

--
-- Name: saga_attempts; Type: ROW SECURITY; Schema: orchestration; Owner: -
--

ALTER TABLE orchestration.saga_attempts ENABLE ROW LEVEL SECURITY;

--
-- Name: saga_attempts saga_attempts_tenant_isolation; Type: POLICY; Schema: orchestration; Owner: -
--

CREATE POLICY saga_attempts_tenant_isolation ON orchestration.saga_attempts USING ((tenant_id = orchestration.current_tenant())) WITH CHECK ((tenant_id = orchestration.current_tenant()));

--
-- Name: saga_attempts saga_attempts_worker_access; Type: POLICY; Schema: orchestration; Owner: -
--

CREATE POLICY saga_attempts_worker_access ON orchestration.saga_attempts USING ((CURRENT_USER = 'core_worker'::name)) WITH CHECK ((CURRENT_USER = 'core_worker'::name));

--
-- Name: sagas; Type: ROW SECURITY; Schema: orchestration; Owner: -
--

ALTER TABLE orchestration.sagas ENABLE ROW LEVEL SECURITY;

--
-- Name: sagas sagas_tenant_isolation; Type: POLICY; Schema: orchestration; Owner: -
--

CREATE POLICY sagas_tenant_isolation ON orchestration.sagas USING ((tenant_id = orchestration.current_tenant())) WITH CHECK ((tenant_id = orchestration.current_tenant()));

--
-- Name: sagas sagas_worker_access; Type: POLICY; Schema: orchestration; Owner: -
--

CREATE POLICY sagas_worker_access ON orchestration.sagas USING ((CURRENT_USER = 'core_worker'::name)) WITH CHECK ((CURRENT_USER = 'core_worker'::name));

--
-- Name: tills; Type: ROW SECURITY; Schema: orchestration; Owner: -
--

ALTER TABLE orchestration.tills ENABLE ROW LEVEL SECURITY;

--
-- Name: tills tills_tenant_isolation; Type: POLICY; Schema: orchestration; Owner: -
--

CREATE POLICY tills_tenant_isolation ON orchestration.tills USING ((tenant_id = orchestration.current_tenant())) WITH CHECK ((tenant_id = orchestration.current_tenant()));

--
-- Name: SCHEMA orchestration; Type: ACL; Schema: -; Owner: -
--

GRANT USAGE ON SCHEMA orchestration TO core_orchestration;
GRANT USAGE ON SCHEMA orchestration TO core_relay;
GRANT USAGE ON SCHEMA orchestration TO core_worker;

--
-- Name: TABLE approvals; Type: ACL; Schema: orchestration; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE orchestration.approvals TO core_orchestration;

--
-- Name: TABLE internal_accounts; Type: ACL; Schema: orchestration; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE orchestration.internal_accounts TO core_orchestration;

--
-- Name: TABLE limit_reservations; Type: ACL; Schema: orchestration; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE orchestration.limit_reservations TO core_orchestration;
GRANT SELECT,UPDATE ON TABLE orchestration.limit_reservations TO core_worker;

--
-- Name: TABLE ops_cases; Type: ACL; Schema: orchestration; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE orchestration.ops_cases TO core_orchestration;
GRANT SELECT,INSERT,UPDATE ON TABLE orchestration.ops_cases TO core_worker;

--
-- Name: TABLE outbox_events; Type: ACL; Schema: orchestration; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE orchestration.outbox_events TO core_orchestration;
GRANT SELECT,UPDATE ON TABLE orchestration.outbox_events TO core_relay;

--
-- Name: SEQUENCE outbox_events_id_seq; Type: ACL; Schema: orchestration; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE orchestration.outbox_events_id_seq TO core_orchestration;
GRANT SELECT,USAGE ON SEQUENCE orchestration.outbox_events_id_seq TO core_worker;

--
-- Name: TABLE reconciliation_findings; Type: ACL; Schema: orchestration; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE orchestration.reconciliation_findings TO core_orchestration;
GRANT SELECT,INSERT ON TABLE orchestration.reconciliation_findings TO core_worker;

--
-- Name: SEQUENCE reconciliation_findings_id_seq; Type: ACL; Schema: orchestration; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE orchestration.reconciliation_findings_id_seq TO core_orchestration;
GRANT SELECT,USAGE ON SEQUENCE orchestration.reconciliation_findings_id_seq TO core_worker;

--
-- Name: TABLE saga_attempts; Type: ACL; Schema: orchestration; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE orchestration.saga_attempts TO core_orchestration;
GRANT SELECT,INSERT ON TABLE orchestration.saga_attempts TO core_worker;

--
-- Name: SEQUENCE saga_attempts_id_seq; Type: ACL; Schema: orchestration; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE orchestration.saga_attempts_id_seq TO core_orchestration;
GRANT SELECT,USAGE ON SEQUENCE orchestration.saga_attempts_id_seq TO core_worker;

--
-- Name: SEQUENCE transaction_reference_seq; Type: ACL; Schema: orchestration; Owner: -
--

GRANT SELECT,USAGE ON SEQUENCE orchestration.transaction_reference_seq TO core_orchestration;

--
-- Name: TABLE sagas; Type: ACL; Schema: orchestration; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE orchestration.sagas TO core_orchestration;
GRANT SELECT,UPDATE ON TABLE orchestration.sagas TO core_worker;

--
-- Name: TABLE tills; Type: ACL; Schema: orchestration; Owner: -
--

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE orchestration.tills TO core_orchestration;

--
-- Name: DEFAULT PRIVILEGES FOR SEQUENCES; Type: DEFAULT ACL; Schema: orchestration; Owner: -
--

ALTER DEFAULT PRIVILEGES FOR ROLE fincore IN SCHEMA orchestration GRANT SELECT,USAGE ON SEQUENCES TO core_orchestration;

--
-- Name: DEFAULT PRIVILEGES FOR TABLES; Type: DEFAULT ACL; Schema: orchestration; Owner: -
--

ALTER DEFAULT PRIVILEGES FOR ROLE fincore IN SCHEMA orchestration GRANT SELECT,INSERT,DELETE,UPDATE ON TABLES TO core_orchestration;

--
--
