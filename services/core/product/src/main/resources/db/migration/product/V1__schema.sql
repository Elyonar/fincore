-- Product module's schema.
--
-- Product answers questions — is this permitted, what fee, what limit, under which configuration
-- version — and never returns postings. Tables arrive with the implementation; this migration
-- establishes the schema and the privilege boundary, because the boundary is the part that is
-- expensive to retrofit.

CREATE SCHEMA IF NOT EXISTS product;

-- The module's own role may use its schema and nothing else. Its neighbours are not granted here
-- and are not granted anywhere: a cross-module query fails at runtime rather than in review.
GRANT USAGE ON SCHEMA product TO core_product;

-- Applies to tables this migration has not created yet, so the grant does not have to be
-- remembered again on every future migration.
ALTER DEFAULT PRIVILEGES IN SCHEMA product
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO core_product;
ALTER DEFAULT PRIVILEGES IN SCHEMA product
    GRANT USAGE, SELECT ON SEQUENCES TO core_product;
