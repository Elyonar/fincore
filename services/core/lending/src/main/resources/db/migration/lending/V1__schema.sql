-- Lending module's schema (ADR 0013).
--
-- Origination, schedules, allocation, accrual facts and delinquency classification. Nothing here
-- owns money: balances live in the Ledger, and every movement this module wants is an
-- Orchestration saga. The boundary is the point — a module that decided *and* posted would be a
-- second path to the Ledger wearing a domain's name.

CREATE SCHEMA IF NOT EXISTS lending;

GRANT USAGE ON SCHEMA lending TO core_lending;

ALTER DEFAULT PRIVILEGES IN SCHEMA lending
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO core_lending;
ALTER DEFAULT PRIVILEGES IN SCHEMA lending
    GRANT USAGE, SELECT ON SEQUENCES TO core_lending;
