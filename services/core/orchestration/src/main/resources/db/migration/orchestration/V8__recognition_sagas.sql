-- Income recognition sagas (lending.md v1.17).
--
-- Recognition moves collected interest (and penalties) from the loan funding account into the
-- product's income account — institution-internal, so it is a funding saga like DISBURSEMENT:
-- no customer checks, no product evaluation, no limit reservation. What makes it a saga is what
-- makes anything here a saga: the derived idempotency key (per repayment, replay-stable by
-- construction), the fingerprint, the three-valued outcome, the worker, the ops case — and the
-- reconciliation job proves its postings like any other's.
ALTER TABLE orchestration.sagas DROP CONSTRAINT sagas_type_check;
ALTER TABLE orchestration.sagas
    ADD CONSTRAINT sagas_type_check
    CHECK (type IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL', 'REVERSAL', 'DISBURSEMENT', 'REPAYMENT',
                    'RECOGNITION'));
