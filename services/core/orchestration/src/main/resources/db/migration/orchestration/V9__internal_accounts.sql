-- =============================================================================================
-- The institution's own accounts, and the names they are known by.
--
-- Until now nothing on this platform could create a ledger account. `LedgerClient` had post,
-- reverse, read and a GET-only passthrough; the edge deliberately has no ledger route; no seeding
-- script writes one. Every downstream feature that names a ledger account — a till, a fee
-- destination, a loan funding source — demanded a UUID that only a raw call to the ledger's own
-- port could produce. An institution could be provisioned and staffed and could not take a
-- deposit.
--
-- This table is the other half of the fix. The ledger owns the account and the money in it; it
-- deliberately stores no name, no code and no purpose (`ledger.accounts` has carried the same
-- eleven columns since V1). That minimalism is right for a ledger and useless to an operator, who
-- needs to pick "Fee income — NGN" from a list rather than paste a UUID. So Core keeps the
-- register: what each of the institution's accounts is called, what it is for, and which ledger
-- account it is.
--
-- It lives in orchestration because AGENTS.md hard rule 3 is unambiguous — orchestration is the
-- only module that may call the ledger, and opening an account is calling the ledger.
-- =============================================================================================

CREATE TABLE orchestration.internal_accounts (
    id                UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id         UUID        NOT NULL,

    -- The ledger account this names. No foreign key is possible — the ledger is a separate
    -- deployable with its own database (ADR 0006) — so the uniqueness below is what stops one
    -- ledger account being registered twice under two different names.
    ledger_account_id UUID        NOT NULL,

    -- The institution's own short reference, e.g. `fee-income-ngn`, `till-01`. Chosen by the
    -- administrator, permanent, and how a configuration screen refers to the account without
    -- showing a UUID.
    code              TEXT        NOT NULL,
    name              TEXT        NOT NULL,

    -- What the account is FOR, which is a different question from what the ledger calls its type.
    -- The ledger's six types describe posting behaviour; these describe the bank's intent, and the
    -- mapping between them is made once, in code, rather than left to whoever fills in the form.
    purpose           TEXT        NOT NULL CHECK (purpose IN (
                          'TILL',              -- a teller's cash drawer
                          'VAULT',             -- branch cash held outside a till
                          'FEE_INCOME',        -- where fee_rules.fee_account_id points
                          'INTEREST_INCOME',   -- loan_rules.interest_income_account_id
                          'PENALTY_INCOME',    -- loan_rules.penalty_income_account_id
                          'LOAN_FUNDING',      -- loan_rules.funding_account_id
                          'SUSPENSE',          -- residue sweeps out of closed accounts
                          'SETTLEMENT',        -- a mirror of a position held at a partner
                          'OTHER')),

    currency          CHAR(3)     NOT NULL,
    status            TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CLOSED')),
    opened_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    opened_by         TEXT        NOT NULL,
    closed_at         TIMESTAMPTZ,
    closed_by         TEXT,

    PRIMARY KEY (id),
    -- The same composite the rest of this schema carries, so a cross-tenant reference is
    -- structurally impossible rather than merely unlikely.
    UNIQUE (tenant_id, id)
);

-- A code is how everything else names this account, so it cannot be ambiguous and cannot be
-- recycled — including by a difference in case, which is how "TILL-01" and "till-01" become two
-- accounts that a report presents as one.
CREATE UNIQUE INDEX internal_accounts_code_per_tenant
    ON orchestration.internal_accounts (tenant_id, lower(code));

-- One registration per ledger account. Two names for one account is two lines in a chart of
-- accounts whose balances always agree, which reads as a reconciliation problem forever.
CREATE UNIQUE INDEX internal_accounts_one_name_per_ledger_account
    ON orchestration.internal_accounts (tenant_id, ledger_account_id);

CREATE INDEX internal_accounts_by_purpose
    ON orchestration.internal_accounts (tenant_id, purpose)
    WHERE status = 'ACTIVE';

ALTER TABLE orchestration.internal_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE orchestration.internal_accounts FORCE ROW LEVEL SECURITY;
CREATE POLICY internal_accounts_tenant_isolation ON orchestration.internal_accounts
    USING (tenant_id = orchestration.current_tenant())
    WITH CHECK (tenant_id = orchestration.current_tenant());

-- ---------------------------------------------------------------------------------------------
-- Tills stop taking a bare UUID.
--
-- `POST /v1/tills` asked for a `ledgerAccountId` and stored whatever arrived — an account that
-- might belong to a customer, might be in another currency, might not exist. Now a till names an
-- internal account by code, and this column records which one, so "which till is this account" and
-- "which account is this till" both have answers.
--
-- Nullable, because tills provisioned before this migration have no answer and inventing one would
-- be worse than admitting it.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE orchestration.tills
    ADD COLUMN internal_account_id UUID,
    ADD CONSTRAINT tills_internal_account_fk
        FOREIGN KEY (tenant_id, internal_account_id)
        REFERENCES orchestration.internal_accounts (tenant_id, id);

-- No seeding here. Migrations run as the owner with no tenant context against FORCE row-level
-- security, so an INSERT over tenants would appear to succeed and write nothing — the lesson V3
-- and V5 of the identity schema record. More importantly, opening an account is a call to another
-- service: it cannot happen inside a migration at all. The institution opens its own accounts from
-- Settings, and the getting-started checklist tracks whether it has.
