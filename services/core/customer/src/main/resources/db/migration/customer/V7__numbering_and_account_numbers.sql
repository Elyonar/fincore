-- =============================================================================================
-- How this institution numbers its customers, and their accounts.
--
-- `external_ref` is described in V2 as "the tenant's own customer number" and is `NOT NULL` with
-- no default, taken verbatim from the request body. So every branch invents its own scheme, and
-- the first duplicate is discovered by a 409 at the counter with a customer standing there.
--
-- The identity service solved this exact problem for staff — `auth.staff_numbering`, claimed under
-- a row lock inside the creating transaction — and the customer side never got it. This is that,
-- twice: once for customers and once for their accounts.
--
-- An account number is the more consequential of the two. A ledger account is addressable only by
-- UUID (`ledger.accounts` has no number column and is not going to grow one — the ledger holds no
-- customer-facing identifiers). A teller cannot read a UUID down a phone line, a customer cannot
-- quote one on a deposit slip, and inbound settlement resolves by number. So the number lives
-- here, beside the link that says whose account it is.
-- =============================================================================================

CREATE TABLE customer.numbering (
    tenant_id  UUID        NOT NULL,

    -- Two series per institution, in one table because they are one decision made twice and an
    -- institution changes them at the same moment. CUSTOMER numbers `customer.customers`,
    -- ACCOUNT numbers `customer.customer_accounts`.
    series     TEXT        NOT NULL CHECK (series IN ('CUSTOMER', 'ACCOUNT')),

    prefix     TEXT        NOT NULL DEFAULT '',
    width      INT         NOT NULL DEFAULT 10 CHECK (width BETWEEN 1 AND 20),
    next_value BIGINT      NOT NULL DEFAULT 1 CHECK (next_value >= 1),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by TEXT        NOT NULL,

    PRIMARY KEY (tenant_id, series)
);

ALTER TABLE customer.numbering ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer.numbering FORCE ROW LEVEL SECURITY;
CREATE POLICY numbering_tenant_isolation ON customer.numbering
    USING (tenant_id = customer.current_tenant())
    WITH CHECK (tenant_id = customer.current_tenant());

-- ---------------------------------------------------------------------------------------------
-- The account number itself.
--
-- Nullable, because accounts linked before this migration have none and inventing one after the
-- fact would print a number on a statement that no earlier statement carried.
--
-- The default width of 10 is not an accident: a Nigerian NUBAN is ten digits, and an institution
-- deploying here almost certainly wants its account numbers to be dialable into the national
-- switch. The check digit that makes a NUBAN a NUBAN is a country-pack concern and is not
-- computed here — this is a serial number of the right shape, and the pack that adds the
-- algorithm will find the column waiting rather than needing a migration.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE customer.customer_accounts ADD COLUMN account_number TEXT;

-- Unique while live. A closed account keeps its number in history — a statement issued under it
-- must stay explicable — and a partial index is what lets both be true.
CREATE UNIQUE INDEX customer_accounts_number_per_tenant
    ON customer.customer_accounts (tenant_id, account_number)
    WHERE account_number IS NOT NULL AND unlinked_at IS NULL;

-- Looked up by number far more often than by anything else: it is what a customer quotes and what
-- an inbound transfer names.
CREATE INDEX customer_accounts_by_number
    ON customer.customer_accounts (tenant_id, account_number)
    WHERE account_number IS NOT NULL;

-- No seeding, for the reason every migration on this platform gives: this runs as the owner with
-- no tenant context against FORCE row-level security, so an INSERT over tenants would appear to
-- succeed and write nothing. The row is created on first use, at defaults, and an institution that
-- arrives with its own numbering changes it under Settings before it opens anything.
