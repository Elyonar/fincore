-- Income recognition and the penalty engine (lending.md v1.17).
--
-- Counters, never a stored "due": interest and penalty dues are always subtractions between a
-- lifetime charged/collected pair, so there is no third number to drift. Recognition state is a
-- mark on the repayment — posted, or an explicit unconfigured no-op — and the loan's
-- recognized_interest_minor advances only by amounts actually posted, which keeps
-- collected-vs-recognized provable by subtraction at any moment.

ALTER TABLE lending.loans
    ADD COLUMN interest_paid_minor      BIGINT NOT NULL DEFAULT 0 CHECK (interest_paid_minor >= 0),
    ADD COLUMN recognized_interest_minor BIGINT NOT NULL DEFAULT 0 CHECK (recognized_interest_minor >= 0),
    ADD COLUMN penalty_charged_minor    BIGINT NOT NULL DEFAULT 0 CHECK (penalty_charged_minor >= 0),
    ADD COLUMN penalty_paid_minor       BIGINT NOT NULL DEFAULT 0 CHECK (penalty_paid_minor >= 0),
    ADD COLUMN penalty_through          DATE;

-- Penalties never collect beyond what was charged.
ALTER TABLE lending.loans
    ADD CONSTRAINT penalty_paid_within_charged CHECK (penalty_paid_minor <= penalty_charged_minor);

-- Existing loans: penalties start counting from where accrual stands, not retroactively.
UPDATE lending.loans SET penalty_through = accrual_through WHERE penalty_through IS NULL;
ALTER TABLE lending.loans ALTER COLUMN penalty_through SET NOT NULL;

-- The once-per-installment flat penalty is arbitrated by this mark, the way one_transition_per_day
-- arbitrates delinquency: a rerun finds nothing unmarked and charges nothing.
ALTER TABLE lending.loan_schedule
    ADD COLUMN penalty_applied_at TIMESTAMPTZ;

-- Recognition resolved: the income-side sagas posted (or there was, explicitly, nothing to post).
ALTER TABLE lending.repayments
    ADD COLUMN recognized_at TIMESTAMPTZ;

CREATE INDEX repayments_unrecognized ON lending.repayments (state)
    WHERE state = 'ALLOCATED' AND recognized_at IS NULL;
