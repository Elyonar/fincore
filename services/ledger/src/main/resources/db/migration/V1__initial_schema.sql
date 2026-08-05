-- Ledger — initial schema.
--
-- Design: services/ledger/docs/data-model.md (AGREED v1.0).
--
-- The rules that make this ledger correct live HERE, not in application code.
-- Anything a careless service, a maintenance script, or a `psql` session could
-- otherwise do to money is refused by a constraint or a trigger. Every object
-- below is asserted to exist AND to fire by the schema-presence suite; a
-- migration that quietly drops one fails CI rather than an audit.
--
-- Nine tables. Amounts are integer minor units. tenant_id on every row.

-- ---------------------------------------------------------------------------
-- Reference data
-- ---------------------------------------------------------------------------

CREATE TABLE currencies (
    code                CHAR(3)     PRIMARY KEY,
    minor_unit_exponent INT         NOT NULL CHECK (minor_unit_exponent BETWEEN 0 AND 4),
    display_name        TEXT        NOT NULL
);

COMMENT ON COLUMN currencies.minor_unit_exponent IS
    'Pins what a minor unit IS (NGN 2, JPY 0). Immutable once referenced: changing it '
    'would reinterpret every stored amount in every tenant at once.';

-- ---------------------------------------------------------------------------
-- Tenant configuration — append-only versions
-- ---------------------------------------------------------------------------

CREATE TABLE tenant_config (
    tenant_id            UUID        NOT NULL,
    version              INT         NOT NULL,
    business_timezone    TEXT        NOT NULL,
    backdate_window_days INT         NOT NULL DEFAULT 30  CHECK (backdate_window_days >= 0),
    max_hold_ttl         INTERVAL    NOT NULL DEFAULT INTERVAL '30 days',
    max_entries_per_tx   INT         NOT NULL DEFAULT 100 CHECK (max_entries_per_tx BETWEEN 2 AND 100),
    effective_from       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by           TEXT        NOT NULL,
    PRIMARY KEY (tenant_id, version)
);

COMMENT ON TABLE tenant_config IS
    'Append-only. The applicable row is the highest version whose effective_from <= now(). '
    'Validation always uses the config live at posting time, never at the entry value_date.';

-- ---------------------------------------------------------------------------
-- Accounts
-- ---------------------------------------------------------------------------

CREATE TABLE accounts (
    id              UUID        PRIMARY KEY,
    tenant_id       UUID        NOT NULL,
    idempotency_key TEXT        NOT NULL CHECK (length(idempotency_key) <= 200),
    type            TEXT        NOT NULL CHECK (type IN
                        ('CUSTOMER','INTERNAL','FEE','SUSPENSE','AGENT_FLOAT','SETTLEMENT_MIRROR')),
    currency        CHAR(3)     NOT NULL REFERENCES currencies (code),
    status          TEXT        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED')),
    customer_ref    TEXT,
    group_ref       TEXT,
    allow_negative  BOOLEAN     NOT NULL DEFAULT FALSE,
    closed_by       TEXT,
    opened_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at       TIMESTAMPTZ,

    -- Composite target so every child FK can carry tenant_id and make a
    -- cross-tenant reference structurally impossible.
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, idempotency_key),

    -- Closure is attributed or it did not happen.
    CONSTRAINT accounts_closure_attributed CHECK (
        (status = 'OPEN'   AND closed_by IS NULL     AND closed_at IS NULL)
     OR (status = 'CLOSED' AND closed_by IS NOT NULL AND closed_at IS NOT NULL)
    )
);

CREATE INDEX accounts_by_tenant_group ON accounts (tenant_id, group_ref) WHERE group_ref IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Balances — derived, provable, one row per account, locked in a stated order
-- ---------------------------------------------------------------------------

CREATE TABLE balances (
    account_id         UUID        PRIMARY KEY,
    tenant_id          UUID        NOT NULL,
    current_minor      BIGINT      NOT NULL DEFAULT 0,
    holds_total_minor  BIGINT      NOT NULL DEFAULT 0 CHECK (holds_total_minor >= 0),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (tenant_id, account_id),
    FOREIGN KEY (tenant_id, account_id) REFERENCES accounts (tenant_id, id)
);

COMMENT ON TABLE balances IS
    'A cache of SUM(credits) - SUM(debits), provable against entries at any time. '
    'available = current_minor - holds_total_minor.';

-- ---------------------------------------------------------------------------
-- Accounting periods
-- ---------------------------------------------------------------------------

CREATE TABLE accounting_periods (
    tenant_id  UUID        NOT NULL,
    period_end DATE        NOT NULL,
    closed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_by  TEXT        NOT NULL,
    PRIMARY KEY (tenant_id, period_end)
);

COMMENT ON TABLE accounting_periods IS
    'A closed period rejects postings whose value_date falls inside it, which is what '
    'makes a signed-off statement reproducible forever. No reopen.';

-- ---------------------------------------------------------------------------
-- Transactions
-- ---------------------------------------------------------------------------

CREATE TABLE ledger_transactions (
    id                         UUID        PRIMARY KEY,
    tenant_id                  UUID        NOT NULL,
    idempotency_key            TEXT        NOT NULL CHECK (length(idempotency_key) <= 200),
    request_fingerprint        TEXT        NOT NULL,
    status                     TEXT        NOT NULL DEFAULT 'POSTED' CHECK (status IN ('POSTED','REVERSED')),
    initiated_by               TEXT        NOT NULL,
    executed_by                TEXT        NOT NULL,
    reverses_transaction_id    UUID,
    relates_to_transaction_id  UUID,
    backdate_reason            TEXT,
    posted_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, idempotency_key),

    FOREIGN KEY (tenant_id, reverses_transaction_id)
        REFERENCES ledger_transactions (tenant_id, id),
    FOREIGN KEY (tenant_id, relates_to_transaction_id)
        REFERENCES ledger_transactions (tenant_id, id),

    -- A transaction cannot be both an undo and a partial correction.
    CONSTRAINT tx_reversal_xor_compensation CHECK (
        reverses_transaction_id IS NULL OR relates_to_transaction_id IS NULL
    ),
    CONSTRAINT tx_no_self_reference CHECK (
        reverses_transaction_id IS DISTINCT FROM id
        AND relates_to_transaction_id IS DISTINCT FROM id
    )
);

-- Hard rule: an original may be reversed at most once. The partial unique index
-- is the arbiter, so a concurrent second reversal loses at the database rather
-- than in a race in application code.
CREATE UNIQUE INDEX ledger_transactions_one_reversal_per_original
    ON ledger_transactions (tenant_id, reverses_transaction_id)
    WHERE reverses_transaction_id IS NOT NULL;

CREATE INDEX ledger_transactions_compensations
    ON ledger_transactions (tenant_id, relates_to_transaction_id)
    WHERE relates_to_transaction_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Entries — append-only, the 7-year audit record
-- ---------------------------------------------------------------------------

CREATE TABLE entries (
    id             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id UUID        NOT NULL,
    account_id     UUID        NOT NULL,
    tenant_id      UUID        NOT NULL,
    direction      TEXT        NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount_minor   BIGINT      NOT NULL CHECK (amount_minor > 0 AND amount_minor <= 1000000000000000),
    currency       CHAR(3)     NOT NULL REFERENCES currencies (code),
    value_date     DATE        NOT NULL,
    booked_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    FOREIGN KEY (tenant_id, transaction_id) REFERENCES ledger_transactions (tenant_id, id),
    FOREIGN KEY (tenant_id, account_id)     REFERENCES accounts (tenant_id, id)
);

COMMENT ON COLUMN entries.amount_minor IS
    'Positive, capped at 10^15 — below BIGINT-sum overflow and below 2^53, so an entry '
    'amount is exact in every JSON consumer. Balances are uncapped sums and are '
    'serialized as decimal strings instead.';
COMMENT ON COLUMN entries.value_date IS 'Business date: when it counts.';
COMMENT ON COLUMN entries.booked_at  IS 'Booking date: when the ledger recorded it.';

CREATE INDEX entries_statement ON entries (tenant_id, account_id, value_date, id);
CREATE INDEX entries_by_transaction ON entries (tenant_id, transaction_id);

-- ---------------------------------------------------------------------------
-- Holds
-- ---------------------------------------------------------------------------

CREATE TABLE holds (
    id                         UUID        PRIMARY KEY,
    tenant_id                  UUID        NOT NULL,
    idempotency_key            TEXT        NOT NULL CHECK (length(idempotency_key) <= 200),
    account_id                 UUID        NOT NULL,
    amount_minor               BIGINT      NOT NULL CHECK (amount_minor > 0 AND amount_minor <= 1000000000000000),
    currency                   CHAR(3)     NOT NULL REFERENCES currencies (code),
    status                     TEXT        NOT NULL DEFAULT 'ACTIVE'
                                   CHECK (status IN ('ACTIVE','RELEASED','EXPIRED','CONSUMED')),
    expires_at                 TIMESTAMPTZ NOT NULL,
    consumed_by_transaction_id UUID,
    placed_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at                TIMESTAMPTZ,

    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, idempotency_key),

    FOREIGN KEY (tenant_id, account_id) REFERENCES accounts (tenant_id, id),
    FOREIGN KEY (tenant_id, consumed_by_transaction_id)
        REFERENCES ledger_transactions (tenant_id, id),

    -- Terminal states carry their resolution; ACTIVE holds have not resolved.
    CONSTRAINT holds_resolution_consistent CHECK (
        (status = 'ACTIVE'   AND resolved_at IS NULL     AND consumed_by_transaction_id IS NULL)
     OR (status = 'CONSUMED' AND resolved_at IS NOT NULL AND consumed_by_transaction_id IS NOT NULL)
     OR (status IN ('RELEASED','EXPIRED') AND resolved_at IS NOT NULL
                                          AND consumed_by_transaction_id IS NULL)
    )
);

COMMENT ON COLUMN holds.expires_at IS
    'NOT NULL by rule: an unbounded hold is a permanent lien on customer funds.';

CREATE INDEX holds_active_by_account ON holds (tenant_id, account_id) WHERE status = 'ACTIVE';
CREATE INDEX holds_expiry_sweep      ON holds (expires_at)            WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- Outbox — a delivery queue, never a second audit archive
-- ---------------------------------------------------------------------------

CREATE TABLE outbox_events (
    id           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    event_type   TEXT        NOT NULL,
    aggregate_id TEXT        NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

-- The relay polls pending rows only; this keeps that poll O(pending) rather
-- than O(history). Never a watermark: ids are assigned at insert, not commit.
CREATE INDEX outbox_pending ON outbox_events (id) WHERE published_at IS NULL;

COMMENT ON COLUMN outbox_events.created_at IS
    'The age the staleness alert measures (oldest unpublished > 60s means the relay is down).';

-- ===========================================================================
-- Tamper evidence — enforced by trigger, so it does not depend on application
-- code being correct or on a maintenance script being careful.
-- ===========================================================================

CREATE OR REPLACE FUNCTION reject_mutation() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION
        'append-only: % on % is not permitted; corrections are reversing entries',
        TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER entries_are_append_only
    BEFORE UPDATE OR DELETE ON entries
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- Account identity is immutable. Status, closure attribution and group_ref may
-- change; what the account *is* may not.
CREATE OR REPLACE FUNCTION reject_account_identity_change() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.currency IS DISTINCT FROM OLD.currency
       OR NEW.type     IS DISTINCT FROM OLD.type
       OR NEW.id       IS DISTINCT FROM OLD.id THEN
        RAISE EXCEPTION
            'account identity is immutable: id, tenant_id, currency and type cannot change'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER accounts_identity_is_immutable
    BEFORE UPDATE ON accounts
    FOR EACH ROW EXECUTE FUNCTION reject_account_identity_change();

-- A reversal's target must not itself be a reversal. Reversing a reversal
-- resurrects money movement while every status still reads terminal.
CREATE OR REPLACE FUNCTION reject_reversal_of_reversal() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.reverses_transaction_id IS NOT NULL
       AND EXISTS (
            SELECT 1 FROM ledger_transactions t
            WHERE t.tenant_id = NEW.tenant_id
              AND t.id        = NEW.reverses_transaction_id
              AND t.reverses_transaction_id IS NOT NULL
       ) THEN
        RAISE EXCEPTION
            'a reversal may not target another reversal; post a fresh correction instead'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER ledger_transactions_no_reversal_of_reversal
    BEFORE INSERT OR UPDATE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION reject_reversal_of_reversal();

-- The currency exponent reinterprets every stored amount at once. Once any
-- account references a currency, its exponent is frozen: a redenomination is a
-- new code and a migration, never an UPDATE.
CREATE OR REPLACE FUNCTION reject_exponent_change_in_use() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.minor_unit_exponent IS DISTINCT FROM OLD.minor_unit_exponent
       AND EXISTS (SELECT 1 FROM accounts WHERE currency = OLD.code) THEN
        RAISE EXCEPTION
            'currency % is in use: minor_unit_exponent is immutable because changing it '
            'would silently reinterpret every amount already recorded', OLD.code
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER currencies_exponent_is_immutable_in_use
    BEFORE UPDATE ON currencies
    FOR EACH ROW EXECUTE FUNCTION reject_exponent_change_in_use();

-- An entry must match the currency of the account it posts against. Expressed
-- as a trigger because it spans two tables and a FK cannot say it.
CREATE OR REPLACE FUNCTION enforce_entry_currency_matches_account() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    account_currency CHAR(3);
BEGIN
    SELECT a.currency INTO account_currency
      FROM accounts a
     WHERE a.tenant_id = NEW.tenant_id AND a.id = NEW.account_id;

    IF account_currency IS DISTINCT FROM NEW.currency THEN
        RAISE EXCEPTION
            'currency mismatch: % posts % against an account denominated in %',
            TG_TABLE_NAME, NEW.currency, account_currency
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER entries_currency_matches_account
    BEFORE INSERT ON entries
    FOR EACH ROW EXECUTE FUNCTION enforce_entry_currency_matches_account();

CREATE TRIGGER holds_currency_matches_account
    BEFORE INSERT ON holds
    FOR EACH ROW EXECUTE FUNCTION enforce_entry_currency_matches_account();

-- Terminal hold states are terminal.
CREATE OR REPLACE FUNCTION reject_terminal_hold_transition() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'ACTIVE' AND NEW.status IS DISTINCT FROM OLD.status THEN
        RAISE EXCEPTION
            'hold % is already %; terminal states never transition again', OLD.id, OLD.status
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER holds_terminal_states_are_terminal
    BEFORE UPDATE ON holds
    FOR EACH ROW EXECUTE FUNCTION reject_terminal_hold_transition();

-- A closed accounting period never reopens.
CREATE TRIGGER accounting_periods_never_reopen
    BEFORE UPDATE OR DELETE ON accounting_periods
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- Tenant config is append-only: history is what makes a past posting's
-- validation context reconstructible.
CREATE TRIGGER tenant_config_is_append_only
    BEFORE UPDATE OR DELETE ON tenant_config
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- ===========================================================================
-- Row-level security — the backstop for a query that forgot its tenant.
--
-- The application sets the context with SET LOCAL inside the request
-- transaction, never a session-level SET: connections are pooled across
-- tenants, and a leaked session variable would make RLS fail in precisely the
-- case it exists to catch.
-- ===========================================================================

CREATE OR REPLACE FUNCTION current_tenant() RETURNS UUID
LANGUAGE plpgsql STABLE AS $$
DECLARE
    raw TEXT := current_setting('app.tenant_id', true);
BEGIN
    IF raw IS NULL OR raw = '' THEN
        RETURN NULL;   -- no context: RLS matches nothing
    END IF;
    RETURN raw::UUID;
END;
$$;

ALTER TABLE accounts            ENABLE ROW LEVEL SECURITY;
ALTER TABLE balances            ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger_transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE entries             ENABLE ROW LEVEL SECURITY;
ALTER TABLE holds               ENABLE ROW LEVEL SECURITY;
ALTER TABLE accounting_periods  ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_config       ENABLE ROW LEVEL SECURITY;
ALTER TABLE outbox_events       ENABLE ROW LEVEL SECURITY;

CREATE POLICY accounts_tenant_isolation            ON accounts
    USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE POLICY balances_tenant_isolation            ON balances
    USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE POLICY ledger_transactions_tenant_isolation ON ledger_transactions
    USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE POLICY entries_tenant_isolation             ON entries
    USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE POLICY holds_tenant_isolation               ON holds
    USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE POLICY accounting_periods_tenant_isolation  ON accounting_periods
    USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE POLICY tenant_config_tenant_isolation       ON tenant_config
    USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE POLICY outbox_events_tenant_isolation       ON outbox_events
    USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
