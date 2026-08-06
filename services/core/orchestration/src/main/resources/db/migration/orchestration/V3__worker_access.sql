-- The saga worker's access.
--
-- Found by a failing test rather than by design review: the recovery worker claims outstanding
-- sagas across every tenant, so it has no tenant context to be scoped by — and row-level security
-- correctly showed it nothing at all.
--
-- The request path and the worker have genuinely different needs on the same table. `core_orchestration`
-- serves requests and must stay tenant-scoped; the worker must see every tenant's outstanding work.
-- One role cannot be both, so the worker gets its own.
--
-- A policy rather than BYPASSRLS on the role, for the same reason the relay got one: BYPASSRLS
-- would exempt the worker from every table's policies at once, and its business is sagas and their
-- attempts — not customers, not approvals.

GRANT USAGE ON SCHEMA orchestration TO core_worker;
GRANT SELECT, UPDATE ON orchestration.sagas TO core_worker;
GRANT SELECT, INSERT ON orchestration.saga_attempts TO core_worker;
GRANT SELECT, UPDATE ON orchestration.limit_reservations TO core_worker;
GRANT SELECT, INSERT, UPDATE ON orchestration.ops_cases TO core_worker;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA orchestration TO core_worker;

CREATE POLICY sagas_worker_access ON orchestration.sagas
    USING (current_user = 'core_worker') WITH CHECK (current_user = 'core_worker');
CREATE POLICY saga_attempts_worker_access ON orchestration.saga_attempts
    USING (current_user = 'core_worker') WITH CHECK (current_user = 'core_worker');
CREATE POLICY limit_reservations_worker_access ON orchestration.limit_reservations
    USING (current_user = 'core_worker') WITH CHECK (current_user = 'core_worker');
CREATE POLICY ops_cases_worker_access ON orchestration.ops_cases
    USING (current_user = 'core_worker') WITH CHECK (current_user = 'core_worker');
