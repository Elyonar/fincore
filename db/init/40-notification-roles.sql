-- Notification's database and its roles, for local development.
--
-- Two roles rather than one, for the same reason Core separates its relay and worker from its
-- modules: they do different jobs under different tenancy rules. Request traffic and the intake
-- pipeline are always scoped to one tenant and connect as `notification_app`. The send worker
-- claims queued messages across every tenant — it has no tenant context to be scoped by — so it
-- gets its own identity and its own row-level-security policy rather than BYPASSRLS, which would
-- exempt it from every table at once and quietly disable isolation everywhere.
--
-- Neither may be SUPERUSER or BYPASSRLS: PostgreSQL skips row-level security entirely for either,
-- leaving every tenant policy inert while the catalog still reports it enabled.
--
-- Migrations run as the owner (fincore); traffic connects as these. Production provisions the same
-- roles with real secrets — these passwords exist only so a laptop works out of the box.

CREATE DATABASE notification OWNER fincore;

CREATE ROLE notification_app    LOGIN PASSWORD 'notification_app'    NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
CREATE ROLE notification_worker LOGIN PASSWORD 'notification_worker' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
