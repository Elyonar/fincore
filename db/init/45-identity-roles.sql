-- Identity's database and its role, for local development (ADR 0018).
--
-- Replaces the Keycloak database (the old 60-keycloak-database.sql, retired with the provider).
-- Where that database was vendor-owned end to end — Keycloak ran its own Liquibase migrations, so
-- one identity did both DDL and traffic — this one follows the platform's normal split, because
-- identity is now a FinCore deployable like any other: migrations run as the owner (fincore),
-- traffic connects as a restricted role.
--
-- One role, not two: unlike Notification there is no cross-tenant background worker here. Every
-- request is scoped to one tenant by SET LOCAL, and the service-token and registry paths that
-- carry no tenant do their work through the owner connection, not a second restricted identity.
--
-- Not SUPERUSER and not BYPASSRLS: PostgreSQL skips row-level security entirely for either,
-- leaving every tenant policy inert while the catalog still reports it enabled. This service holds
-- credential hashes and refresh material — it is the last place to weaken isolation.
--
-- The password is a dev convenience with no value outside this container. Production provisions
-- this role with a real secret.

CREATE DATABASE identity OWNER fincore;

CREATE ROLE identity_app LOGIN PASSWORD 'identity_app' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
