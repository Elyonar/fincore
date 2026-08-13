-- Product's database and its role, for local development.
--
-- Its own database rather than a schema inside Core's, because that is what "independent
-- deployable" has to mean here: Core is now a client of this service and holds no credential that
-- can read a product table. A schema in a shared database would have left the old coupling in
-- place, one connection string away from being used again.
--
-- One role, because the service has one job. There is no worker: nothing here is claimed from a
-- queue or drained in the background. Every request is a tenant's, scoped by `SET LOCAL`.
--
-- It may not be SUPERUSER or BYPASSRLS: PostgreSQL skips row-level security entirely for either,
-- leaving every tenant policy inert while the catalog still reports it enabled.
--
-- Migrations run as the owner (fincore); traffic connects as this. Production provisions the same
-- role with a real secret — this password exists only so a laptop works out of the box.

CREATE DATABASE product OWNER fincore;

CREATE ROLE product_app LOGIN PASSWORD 'product_app' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
