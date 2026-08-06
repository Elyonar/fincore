-- Core's database and its per-module roles, for local development.
--
-- One role per module, granted only on its own schema (ADR 0006, ADR 0007). A cross-module query
-- then fails at runtime, in the test suite, on first attempt — rather than surviving until someone
-- tries to extract a module and discovers three years of quiet coupling.
--
-- None of these may be SUPERUSER or BYPASSRLS: PostgreSQL skips row-level security entirely for
-- either, which would leave every tenant policy inert while the catalog still reported it enabled.
--
-- Migrations run as the owner (fincore); traffic connects as these. DDL and traffic are different
-- jobs and must not share an identity. Production provisions the same roles with real secrets —
-- these passwords exist only so a laptop works out of the box.

CREATE DATABASE core OWNER fincore;

CREATE ROLE core_customer      LOGIN PASSWORD 'core_customer'      NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
CREATE ROLE core_product       LOGIN PASSWORD 'core_product'       NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
CREATE ROLE core_orchestration LOGIN PASSWORD 'core_orchestration' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;

-- The outbox relay reads every module's outbox and marks rows published. It is cross-cutting
-- infrastructure rather than a module, so it gets its own identity with a deliberately narrow
-- grant — added with the outbox tables, not here, so the grant can name them.
CREATE ROLE core_relay         LOGIN PASSWORD 'core_relay'         NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;

-- The recovery worker claims outstanding sagas across every tenant, so it has no tenant context to
-- be scoped by. It gets its own role and its own policy rather than BYPASSRLS, which would exempt
-- it from every table at once.
CREATE ROLE core_worker         LOGIN PASSWORD 'core_worker'        NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
