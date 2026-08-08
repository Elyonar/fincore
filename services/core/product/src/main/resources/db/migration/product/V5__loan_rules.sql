-- Loan products carry lending rules the way savings products carry fee rules (lending.md).
--
-- First: LOAN joins the product-type vocabulary (PRD §4.3 named loan products in v1's types;
-- the CHECK predates lending and said otherwise).
ALTER TABLE product.products DROP CONSTRAINT products_type_check;
ALTER TABLE product.products
    ADD CONSTRAINT products_type_check CHECK (type IN ('SAVINGS', 'CURRENT', 'LOAN'));
--
--
-- The rate, the schedule shape, the bounds and the allocation order are *pricing* — versioned,
-- published maker-checked, immutable once live — so they live here, on the product version, and
-- Lending evaluates them the way Orchestration evaluates fees: through a port, pinned by
-- product_version on the loan, explicable after the configuration moves on.
CREATE TABLE product.loan_rules (
    id                         UUID    NOT NULL DEFAULT gen_random_uuid(),
    tenant_id                  UUID    NOT NULL,
    product_version_id         UUID    NOT NULL,
    -- Annual nominal rate in basis points (hard rule 1: integer arithmetic on money and on the
    -- numbers that price it). 2500 means 25% per annum, accrued ACT/365 fixed.
    interest_rate_bp           INT     NOT NULL CHECK (interest_rate_bp >= 0),
    schedule_kind              TEXT    NOT NULL CHECK (schedule_kind IN ('ANNUITY', 'FLAT', 'BULLET')),
    min_amount_minor           BIGINT  NOT NULL CHECK (min_amount_minor > 0),
    max_amount_minor           BIGINT  NOT NULL CHECK (max_amount_minor >= min_amount_minor),
    min_term_months            INT     NOT NULL CHECK (min_term_months > 0),
    max_term_months            INT     NOT NULL CHECK (max_term_months >= min_term_months),
    grace_months               INT     NOT NULL DEFAULT 0 CHECK (grace_months >= 0),
    -- Comma-separated component order for repayment allocation, e.g.
    -- 'PENALTY,FEE,INTEREST,PRINCIPAL'. Text rather than an enum array because the vocabulary is
    -- the lending module's and this schema must not import it; Lending validates on read.
    allocation_order           TEXT    NOT NULL DEFAULT 'PENALTY,FEE,INTEREST,PRINCIPAL',
    -- Where recognized interest lands. Configuration, not a caller assertion — the same reasoning
    -- as fee_rules.fee_account_id (V4).
    interest_income_account_id UUID,
    prepayment_fee_bp          INT     NOT NULL DEFAULT 0 CHECK (prepayment_fee_bp >= 0),
    currency                   CHAR(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, product_version_id) REFERENCES product.product_versions (tenant_id, id),
    -- One rule set per version: a version prices one way.
    CONSTRAINT one_loan_rule_per_version UNIQUE (product_version_id)
);

ALTER TABLE product.loan_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE product.loan_rules FORCE ROW LEVEL SECURITY;

CREATE POLICY loan_rules_tenant_isolation ON product.loan_rules
    USING (tenant_id = product.current_tenant())
    WITH CHECK (tenant_id = product.current_tenant());
