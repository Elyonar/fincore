-- Organizational units arrive on the money path (ADR 0012).
--
-- Two changes, both attribution rather than authorization:
--
-- 1. A till records which organizational unit it belongs to. `branch_code` was free text with no
--    table behind it; it stays (it is what existing rows carry, and the human-legible handle),
--    and `unit_id` ties it to the Organization module's row. No foreign key: the units table is
--    another module's schema, and modules reach each other only through their published
--    interfaces, never their tables (ADR 0006). Orchestration validates the code through the
--    OrganizationUnits port at till creation — the same pattern customer_accounts uses toward
--    the Ledger's accounts.
--
-- 2. An approval records where its maker and checker sat. TEXT snapshots of the token's `units`
--    claim at the moment of signature — historical fact, so no FK either: the answer to "which
--    branch approved this" must not change when someone is reassigned next month.
--
-- Both columns are nullable: rows predating this migration, and tenants that have not adopted
-- organizational units, carry none.
ALTER TABLE orchestration.tills ADD COLUMN unit_id UUID;

ALTER TABLE orchestration.approvals ADD COLUMN made_in_unit TEXT;
ALTER TABLE orchestration.approvals ADD COLUMN checked_in_unit TEXT;
