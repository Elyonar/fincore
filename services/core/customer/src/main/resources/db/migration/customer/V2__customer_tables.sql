-- Customer module's tables.
--
-- This is the only schema on the platform holding PII, which is what makes Customer the first
-- extraction candidate (ADR 0006). Nothing here owns money: balances, entries and transaction
-- history live in the Ledger, and this module holds only who someone is and what they may do.

CREATE OR REPLACE FUNCTION customer.current_tenant() RETURNS UUID
LANGUAGE plpgsql STABLE AS $$
DECLARE
    raw TEXT := current_setting('app.tenant_id', true);
BEGIN
    IF raw IS NULL OR raw = '' THEN
        RETURN NULL;   -- no context: policies match nothing
    END IF;
    RETURN raw::UUID;
END;
$$;

CREATE TABLE customer.customers (
    id           UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id    UUID        NOT NULL,
    external_ref TEXT        NOT NULL,
    status       TEXT        NOT NULL DEFAULT 'ACTIVE'
                             CHECK (status IN ('PROSPECT', 'ACTIVE', 'DORMANT', 'CLOSED')),
    -- A value from a configurable framework, not a hardcoded enumeration of CBN's three tiers
    -- (PRD §3.1). Tiers and their limits are country-pack configuration held by Product.
    kyc_tier     TEXT        NOT NULL DEFAULT 'TIER_1',
    full_name    TEXT        NOT NULL,
    phone        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    -- The tenant's own customer number. Unique within a tenant, meaningless across them.
    CONSTRAINT customers_external_ref_unique UNIQUE (tenant_id, external_ref)
);

-- The customer-to-ledger mapping. The Ledger holds an opaque customer_ref and knows nothing about
-- people; the association lives here, on this side of the PII boundary.
CREATE TABLE customer.customer_accounts (
    id                UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id         UUID        NOT NULL,
    customer_id       UUID        NOT NULL,
    -- No foreign key: this points into another deployable's database. The Ledger is the authority
    -- on whether the account exists; this table is the authority on who holds it.
    ledger_account_id UUID        NOT NULL,
    currency          CHAR(3)     NOT NULL,
    role              TEXT        NOT NULL DEFAULT 'PRIMARY',
    linked_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    unlinked_at       TIMESTAMPTZ,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES customer.customers (tenant_id, id),
    -- One live link per account. An account held by two customers at once would make
    -- "does this customer hold this account" unanswerable.
    CONSTRAINT one_live_holder_per_account UNIQUE (tenant_id, ledger_account_id, unlinked_at)
);

CREATE INDEX customer_accounts_by_customer
    ON customer.customer_accounts (tenant_id, customer_id)
    WHERE unlinked_at IS NULL;

ALTER TABLE customer.customers          ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer.customer_accounts  ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer.customers          FORCE ROW LEVEL SECURITY;
ALTER TABLE customer.customer_accounts  FORCE ROW LEVEL SECURITY;

CREATE POLICY customers_tenant_isolation ON customer.customers
    USING (tenant_id = customer.current_tenant())
    WITH CHECK (tenant_id = customer.current_tenant());
CREATE POLICY customer_accounts_tenant_isolation ON customer.customer_accounts
    USING (tenant_id = customer.current_tenant())
    WITH CHECK (tenant_id = customer.current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA customer TO core_customer;
