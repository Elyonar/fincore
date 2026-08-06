-- A database per deployable, for the test suite, separate from the one the dev stack runs on.
--
-- Why this exists
-- ---------------
-- The suite and `docker compose up` used to share one database per service, and the stack writes
-- to it. Twice that produced failures that looked like real defects and were not:
--
--   * AnchorServiceTest — transaction ids are cluster-wide, so Core's saga worker and outbox relay
--     polling a *different* database in the same PostgreSQL instance held the ledger's quiesce
--     horizon below entries a test had already committed, and capture correctly anchored nothing.
--   * OutboxTest — HoldExpirySweep runs every 30 seconds inside the ledger container, writing
--     HOLD_RELEASED events for holds the suite had created. Landing between a test's drain and its
--     assertion, it shifted the counts by however many holds happened to be swept.
--
-- Both are the same root cause: a running deployable is a concurrent writer, and no amount of
-- draining or waiting inside a test makes it not one. The tests were adjusted the first time; the
-- second occurrence is what says the problem is the shared database, not the assertions.
--
-- What this does not change
-- -------------------------
-- Nothing about the hard rules. Rule 5 — a deployable owns its database, a module owns a schema —
-- still holds: this is the same ownership, applied to a second instance for a different purpose.
-- No deployable reads another's test database, and the per-module roles are unchanged because
-- PostgreSQL roles are cluster-wide. Flyway creates the schemas and issues the grants, exactly as
-- it does for the development databases, so the two stay identical by construction rather than by
-- someone remembering to mirror a change.
--
-- The contract suite is deliberately excluded: LedgerContractTest runs against the *running*
-- Ledger and therefore its development database, because the whole point of it is to exercise a
-- real deployable rather than a schema that looks like one.
--
-- Runs once, on an empty volume, after the roles above exist.

CREATE DATABASE ledger_test       OWNER fincore;
CREATE DATABASE core_test         OWNER fincore;
CREATE DATABASE notification_test OWNER fincore;
