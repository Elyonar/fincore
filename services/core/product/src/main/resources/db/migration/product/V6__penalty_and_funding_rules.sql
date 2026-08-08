-- Penalty rules and account configuration join loan pricing (lending.md v1.17).
--
-- Penalties are *pricing* — versioned, published maker-checked, immutable once live — so they
-- live here, beside the rate they accompany. A flat amount per late installment, basis points
-- per day on overdue principal, and an optional lifetime cap. Zero defaults mean versions that
-- never configure penalties price them to zero explicitly, not implicitly.
ALTER TABLE product.loan_rules
    ADD COLUMN penalty_flat_minor BIGINT NOT NULL DEFAULT 0 CHECK (penalty_flat_minor >= 0),
    ADD COLUMN penalty_rate_bp    INT    NOT NULL DEFAULT 0 CHECK (penalty_rate_bp >= 0),
    ADD COLUMN penalty_cap_minor  BIGINT CHECK (penalty_cap_minor IS NULL OR penalty_cap_minor >= 0),
    -- Where recognized penalties land; null falls back to interest_income_account_id.
    ADD COLUMN penalty_income_account_id UUID,
    -- The tenant's loan funding account, configuration-first on disburse — the
    -- fee_rules.fee_account_id reasoning (V4), applied to the source side.
    ADD COLUMN funding_account_id UUID;
