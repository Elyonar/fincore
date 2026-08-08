-- Organization module's schema.
--
-- The tenant's operational structure: branches, regions, business lines, and who is assigned
-- where (ADR 0012). Deliberately narrow — a unit is operational scope only. Legal entities,
-- booking units and jurisdictions are distinct concepts this module must never absorb, and the
-- ADR records why. Nothing here owns money or PII.

CREATE SCHEMA IF NOT EXISTS organization;

-- The module's own role may use its schema and nothing else. Its neighbours are not granted here
-- and are not granted anywhere: a cross-module query fails at runtime rather than in review.
GRANT USAGE ON SCHEMA organization TO core_organization;

-- Applies to tables this migration has not created yet, so the grant does not have to be
-- remembered again on every future migration.
ALTER DEFAULT PRIVILEGES IN SCHEMA organization
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO core_organization;
ALTER DEFAULT PRIVILEGES IN SCHEMA organization
    GRANT USAGE, SELECT ON SEQUENCES TO core_organization;
