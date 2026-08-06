-- Makes `one_live_holder_per_account` do what its name says.
--
-- V2 wrote it as UNIQUE (tenant_id, ledger_account_id, unlinked_at), with this comment:
--
--     One live link per account. An account held by two customers at once would make
--     "does this customer hold this account" unanswerable.
--
-- It did not do that. A live link has unlinked_at IS NULL, and PostgreSQL treats NULLs as distinct
-- in a UNIQUE constraint — so (t, a, NULL) and (t, a, NULL) are two different keys and both insert
-- happily. The constraint therefore permitted exactly the case it was written to forbid, and only
-- constrained rows that had already been unlinked at the same instant, which is not a rule anyone
-- wanted.
--
-- Nothing caught it because until now no code could create a second link: the schema was reachable
-- only from tests that seeded one row each. The first request through the new
-- POST /v1/customers/{id}/accounts found it immediately.
--
-- This matters beyond tidiness. CustomerEligibility.holdsAccount is asked on every transfer and
-- every cash operation, and answers by looking for *a* live link. With two, "who holds this
-- account" has two answers, and the money path would have been authorising against whichever the
-- planner happened to return.

-- Refuse to proceed rather than choose a winner. If duplicates exist, deciding which customer
-- keeps the account is a business decision about who owns money, and a migration is the last place
-- it should be made silently.
DO $$
DECLARE
    offenders INT;
BEGIN
    SELECT count(*) INTO offenders
      FROM (SELECT tenant_id, ledger_account_id
              FROM customer.customer_accounts
             WHERE unlinked_at IS NULL
             GROUP BY tenant_id, ledger_account_id
            HAVING count(*) > 1) duplicated;

    IF offenders > 0 THEN
        RAISE EXCEPTION
            '% ledger account(s) are live-linked to more than one customer. Resolve them by '
            'setting unlinked_at on the links that should not survive, then re-run. This is not '
            'decided here: which customer holds an account determines who may move its money.',
            offenders
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
END;
$$;

ALTER TABLE customer.customer_accounts DROP CONSTRAINT one_live_holder_per_account;

-- A partial unique index, which is the construct that actually expresses "one *live* holder".
-- Unlinked history is unconstrained, as it should be: an account may be held, released and held
-- again, and each of those is a real row.
CREATE UNIQUE INDEX one_live_holder_per_account
    ON customer.customer_accounts (tenant_id, ledger_account_id)
    WHERE unlinked_at IS NULL;
