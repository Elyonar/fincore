-- An account remembers what it was opened under.
--
-- Until now `customer_accounts` recorded who holds an account, in what currency, in what role —
-- and not what it *is*. The money path takes `productCode` from the request body, so the fee
-- charged and the limit applied to a deposit came from whatever the caller named. The same account
-- could be priced under a savings product in the morning and a current product in the afternoon
-- with nothing inconsistent from the platform's point of view, and a customer's ceiling would
-- depend on a teller's dropdown rather than on the account they hold.
--
-- This is the same correction V4 made for `fee_rules.fee_account_id`: pricing is configuration, not
-- a caller assertion. There the caller could name where fee income landed; here they could name
-- which rules applied at all, which is the larger of the two.
--
-- Nullable rather than NOT NULL, and deliberately. Rows written before this migration have no
-- honest value to backfill — inventing one would assert a product an account was never opened
-- under, and every such row is a statement somebody may one day have to explain. The write path
-- requires the column from here on, and the money path refuses an account that has none
-- (`ACCOUNT_HAS_NO_PRODUCT`) rather than falling back to the request body, because a fallback is
-- how the hole this closes stayed open.
ALTER TABLE customer.customer_accounts
    ADD COLUMN product_code TEXT;

COMMENT ON COLUMN customer.customer_accounts.product_code IS
    'The product this account was opened under. Decides which fee and limit rules a transaction on '
    'it is evaluated against. Null only for accounts linked before the column existed; the money '
    'path refuses those rather than guessing.';

-- Answering "what product is this account" on the cash path is a per-transaction read, and it
-- joins on the same predicate `holdsAccount` already uses.
CREATE INDEX IF NOT EXISTS customer_accounts_live_by_account
    ON customer.customer_accounts (tenant_id, ledger_account_id)
    WHERE unlinked_at IS NULL;
