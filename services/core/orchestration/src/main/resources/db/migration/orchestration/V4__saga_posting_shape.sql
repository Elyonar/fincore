-- The accounts a saga posts to.
--
-- Found by building the recovery worker: to retry an UNKNOWN outcome the worker must send the
-- *identical* posting under the same derived key, and the saga recorded the amount, fee and
-- currency but not where the money was going. A retry that rebuilt a different posting would be
-- rejected as IDEMPOTENCY_KEY_REUSED — turning a recoverable unknown into a permanent one.
--
-- The alternative was reconstructing the posting from the caller's original request, which does not
-- survive a crash. A saga has to carry everything needed to finish itself.

ALTER TABLE orchestration.sagas
    ADD COLUMN from_account_id UUID,
    ADD COLUMN to_account_id   UUID,
    ADD COLUMN fee_account_id  UUID;

-- Transfers and cash operations move money between named accounts; a reversal names none, because
-- it targets a transaction rather than a pair of accounts.
--
-- NOT VALID, deliberately. A plain CHECK is validated against every existing row and fails the
-- migration if any predates the column — which is exactly what happened the first time this ran.
-- NOT VALID enforces the rule on everything written from here on while leaving history alone; it
-- is the expand half of expand/migrate/contract (docs/conventions/service-scaffold.md). Validating
-- it is a later migration, after any rows written before this one are backfilled or aged out.
ALTER TABLE orchestration.sagas
    ADD CONSTRAINT money_movements_name_their_accounts CHECK (
        type = 'REVERSAL'
        OR (from_account_id IS NOT NULL AND to_account_id IS NOT NULL)) NOT VALID;
