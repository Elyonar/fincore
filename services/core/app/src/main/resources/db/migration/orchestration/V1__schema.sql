-- Orchestration module's schema.
--
-- The money path: sagas, limit reservations, approvals, tills, ops cases and the outbox. Tables
-- arrive with the implementation; this migration establishes the schema and the privilege
-- boundary, because the boundary is the part that is expensive to retrofit.
--
-- Note what is *not* here: no grant to core_customer or core_product. The reservation and the saga
-- commit in one local transaction (ADR 0006), and that transaction belongs to this module alone —
-- sharing a database is not the same thing as sharing tables.

CREATE SCHEMA IF NOT EXISTS orchestration;

GRANT USAGE ON SCHEMA orchestration TO core_orchestration;

ALTER DEFAULT PRIVILEGES IN SCHEMA orchestration
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO core_orchestration;
ALTER DEFAULT PRIVILEGES IN SCHEMA orchestration
    GRANT USAGE, SELECT ON SEQUENCES TO core_orchestration;
