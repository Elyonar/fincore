-- Local development only. Runs once, at first container start.
--
-- Creates the runtime role with a login so `docker compose up` and the test
-- suite work out of the box. The password is a dev convenience with no value
-- outside this container — production provisions this role with a real secret,
-- and V3__application_role.sql deliberately sets none.
CREATE ROLE ledger_app LOGIN PASSWORD 'ledger_app' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
