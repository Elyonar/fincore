-- Make row-level security actually apply.
--
-- V1 enabled RLS and wrote a policy per tenant-scoped table, and it did nothing.
-- PostgreSQL exempts a table's OWNER from its own row-level security unless the
-- table is additionally marked FORCE. The ledger connects as the role that owns
-- these tables, so every policy in V1 was inert: with no tenant context set at
-- all, a plain `SELECT * FROM accounts` returned every tenant's rows.
--
-- That is the exact failure RLS exists to prevent, and it failed silently — the
-- catalog reported `relrowsecurity = true`, so a presence check said "RLS is on"
-- while the guarantee was absent. Enabled is not enforced.
--
-- Corrections are new migrations, never edits to an applied one
-- (docs/conventions/design-changes.md), so this arrives as V2 rather than as a
-- fix to V1.

ALTER TABLE accounts            FORCE ROW LEVEL SECURITY;
ALTER TABLE balances            FORCE ROW LEVEL SECURITY;
ALTER TABLE ledger_transactions FORCE ROW LEVEL SECURITY;
ALTER TABLE entries             FORCE ROW LEVEL SECURITY;
ALTER TABLE holds               FORCE ROW LEVEL SECURITY;
ALTER TABLE accounting_periods  FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant_config       FORCE ROW LEVEL SECURITY;
ALTER TABLE outbox_events       FORCE ROW LEVEL SECURITY;

-- Defence in depth beyond this migration: the runtime should connect as a role
-- that neither owns these tables nor holds BYPASSRLS, so that forgetting FORCE
-- on a future table cannot silently reopen the hole. FORCE is the fix that works
-- with the single-role setup the service has today; the separate application
-- role belongs with deployment, and is tracked as a follow-up rather than
-- pretended to here.
