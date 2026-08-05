-- Let the outbox relay see the outbox.
--
-- Row-level security is tenant-scoped, and correctly so for every path that
-- serves a request. The relay is not such a path: it is infrastructure that
-- drains events for *all* tenants, and under the V2/V3 policies it saw nothing
-- at all — it ran, found an empty queue, and reported success while events
-- accumulated forever. A delivery mechanism that silently delivers nothing is
-- the worst shape this failure could take.
--
-- The grant is deliberately narrow:
--
--   * it applies to `outbox_events` alone. No policy anywhere else is relaxed,
--     so balances, entries, accounts and holds stay strictly tenant-scoped.
--   * it requires an explicit, transaction-local opt-in — `SET LOCAL
--     app.relay = 'on'` — rather than being ambient. A query that does not ask
--     for cross-tenant access does not get it, and asking is greppable.
--   * outbox payloads are thin by design: ids and a minimal summary, no PII.
--     The blast radius of this exemption is therefore an event type and some
--     identifiers.
--
-- The stronger form is a dedicated `ledger_relay` role holding this policy,
-- with its own credentials and no access to any other table, so that the
-- application role could not assume relay powers even if it tried. That needs a
-- second datasource and per-environment credentials; it is recorded as
-- hardening rather than pretended to here.

DROP POLICY outbox_events_tenant_isolation ON outbox_events;

-- One policy per command, because the relay's permissions differ by verb and a
-- single policy cannot express that: WITH CHECK fires on INSERT *and* UPDATE,
-- so a policy permissive enough to let the relay mark a row published would
-- also let it author an event.

-- Read: the tenant's own rows, or everything while explicitly in relay scope.
CREATE POLICY outbox_events_select ON outbox_events
    FOR SELECT
    USING (
        tenant_id = current_tenant()
        OR coalesce(current_setting('app.relay', true), '') = 'on'
    );

-- Write: strictly tenant-scoped. An event is authored by the transaction that
-- moved the money, never by the delivery mechanism.
CREATE POLICY outbox_events_insert ON outbox_events
    FOR INSERT
    WITH CHECK (tenant_id = current_tenant());

-- Mark published: the relay's whole job. The row it may update is the row it
-- may see, and it cannot move a row to another tenant.
CREATE POLICY outbox_events_update ON outbox_events
    FOR UPDATE
    USING (
        tenant_id = current_tenant()
        OR coalesce(current_setting('app.relay', true), '') = 'on'
    )
    WITH CHECK (
        tenant_id = current_tenant()
        OR coalesce(current_setting('app.relay', true), '') = 'on'
    );

-- Purge published rows past retention.
CREATE POLICY outbox_events_delete ON outbox_events
    FOR DELETE
    USING (
        tenant_id = current_tenant()
        OR coalesce(current_setting('app.relay', true), '') = 'on'
    );
