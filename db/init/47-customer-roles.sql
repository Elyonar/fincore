-- Customer's database and its role, for local development.
--
-- Its own database, on the same reasoning as Product's: Core is a client now, and the boundary is
-- worth nothing if Core still holds a credential that can read `customer.customers`.
--
-- This is the service most likely to grow — KYC tiers and their history, contact details, consent
-- and its changes, and eventually documents and screening — and the one most likely to be consumed
-- by something that is not Core at all. That is the whole argument for it standing alone.
--
-- It may not be SUPERUSER or BYPASSRLS: PostgreSQL skips row-level security entirely for either,
-- leaving every tenant policy inert while the catalog still reports it enabled.
--
-- Migrations run as the owner (fincore); traffic connects as this. Production provisions the same
-- role with a real secret — this password exists only so a laptop works out of the box.

CREATE DATABASE customer OWNER fincore;

CREATE ROLE customer_app LOGIN PASSWORD 'customer_app' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
