-- Funding sagas: institution-initiated money movement, for Lending (ADR 0013).
--
-- A disbursement moves money from the tenant's loan funding account to a customer; a repayment
-- moves it back; both are ordinary two-entry sagas — replayed, claimed, escalated and reconciled
-- by machinery that already exists. What they are *not* is customer-channel traffic: no product
-- evaluation and no limit reservation, because the exposure was approved by Lending's own
-- amount-tiered chain and channel limits are customer-protection for customer-initiated
-- movement. That asymmetry is the design (lending.md), not an omission.
ALTER TABLE orchestration.sagas DROP CONSTRAINT sagas_type_check;
ALTER TABLE orchestration.sagas
    ADD CONSTRAINT sagas_type_check
    CHECK (type IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL', 'REVERSAL', 'DISBURSEMENT', 'REPAYMENT'));
