-- Customer module's schema.
--
-- This module is the only one holding PII, which is what makes it the first extraction candidate
-- (ADR 0006). Tables arrive with the implementation; this migration establishes the schema and the
-- privilege boundary, because the boundary is the part that is expensive to retrofit.

CREATE SCHEMA IF NOT EXISTS customer;

-- The module's own role may use its schema and nothing else. Its neighbours are not granted here
-- and are not granted anywhere: a cross-module query fails at runtime rather than in review.
GRANT USAGE ON SCHEMA customer TO core_customer;

-- Applies to tables this migration has not created yet, so the grant does not have to be
-- remembered again on every future migration.
ALTER DEFAULT PRIVILEGES IN SCHEMA customer
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO core_customer;
ALTER DEFAULT PRIVILEGES IN SCHEMA customer
    GRANT USAGE, SELECT ON SEQUENCES TO core_customer;
