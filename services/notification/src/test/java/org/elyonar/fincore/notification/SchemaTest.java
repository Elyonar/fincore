package org.elyonar.fincore.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Every object the design depends on exists, and every rule it claims actually fires.
 *
 * <p>Modelled on the ledger's schema-presence and schema-enforcement suites, and for the reason the
 * scaffold convention gives: correctness that lives in database objects is correctness a careless
 * migration can silently drop. A trigger that stopped firing looks exactly like a trigger that
 * never fired, and only a test that tries to violate the rule can tell them apart.
 */
@SpringBootTest
@DisplayName("schema — the rules live in the database, and they fire")
class SchemaTest {

    @Autowired @Qualifier("appJdbcTemplate") private JdbcTemplate app;

    // The owner. Needed because a grant and a trigger protect different callers: the app role has
    // no UPDATE on the append-only tables, so a test using it proves the grant and says nothing
    // about the trigger — and the trigger is what protects the one identity the grant cannot.
    @Autowired private javax.sql.DataSource ownerDataSource;

    @Autowired private org.elyonar.fincore.notification.internal.TenantRegistry tenantRegistry;

    private UUID tenant;

    @BeforeEach
    void scopeToAFreshTenant() {
        tenant = UUID.randomUUID();
        tenantRegistry.register(tenant, "test tenant", "test");
        app.execute("SET app.tenant_id = '" + tenant + "'");
    }

    @Test
    @DisplayName("all seven tables exist")
    void the_tables_exist() {
        List<String> tables = app.queryForList(
                // flyway_schema_history is Flyway's bookkeeping, not this design's. Excluded by
                // name so that adding a table to the design fails this test, which is the point.
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'notification'"
                        + " AND table_name <> 'flyway_schema_history' ORDER BY table_name",
                String.class);

        assertThat(tables)
                .containsExactly(
                        "channel_policy",
                        "channels",
                        "consumed_events",
                        "delivery_attempts",
                        "notifications",
                        "suppressions",
                        "templates",
                        "tenants");
    }

    @Test
    @DisplayName("row-level security is enabled and forced on every tenant-scoped table")
    void rls_is_forced() {
        // ENABLE without FORCE exempts the table owner, and migrations run as the owner — so a
        // table that is merely enabled reports itself protected while the one connection most
        // likely to hold a bug is exempt.
        List<String> unforced = app.queryForList(
                """
                SELECT c.relname FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'notification' AND c.relkind = 'r'
                   -- channels is a deployment fact, not a tenant's. tenants is the registry a
                   -- request consults *before* it has a tenant context to be scoped by — securing
                   -- it by tenant would make "is this tenant real?" unanswerable.
                   AND c.relname NOT IN ('channels', 'tenants', 'flyway_schema_history')
                   AND (c.relrowsecurity IS FALSE OR c.relforcerowsecurity IS FALSE)
                """,
                String.class);

        assertThat(unforced).isEmpty();
    }

    @Test
    @DisplayName("the two v1 channels are registered, with their content models")
    void the_registry_is_seeded() {
        var sms = app.queryForMap("SELECT * FROM notification.channels WHERE id = 'SMS'");
        var email = app.queryForMap("SELECT * FROM notification.channels WHERE id = 'EMAIL'");

        assertThat(sms).containsEntry("address_kind", "PHONE").containsEntry("content_model", "SEGMENTED");
        assertThat(email).containsEntry("address_kind", "EMAIL").containsEntry("content_model", "PLAIN");
        // Adding a channel is an INSERT here and a sender class. If this ever needs a migration to
        // add a *channel*, the registry has stopped being a registry.
        assertThat(app.queryForObject("SELECT count(*) FROM notification.channels", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("a published template missing a part its channel requires is rejected")
    void required_parts_are_enforced() {
        // The guarantee that moved when parts became JSONB (CHANGELOG v1.1). It used to be a
        // NOT NULL on a subject column; it must not have weakened in the move.
        assertThatThrownBy(() -> app.update(
                        """
                        INSERT INTO notification.templates
                               (tenant_id, template_key, channel, locale, version, status, parts, units, published_by)
                        VALUES (?, 'debit.alert', 'EMAIL', 'en', 1, 'PUBLISHED', '{"body":"hello"}'::jsonb, 1, 'test')
                        """,
                        tenant))
                .hasMessageContaining("subject");

        // The same template with the part present is fine.
        assertThat(app.update(
                        """
                        INSERT INTO notification.templates
                               (tenant_id, template_key, channel, locale, version, status, parts, units, published_by)
                        VALUES (?, 'debit.alert', 'EMAIL', 'en', 1, 'PUBLISHED',
                                '{"subject":"Debit","body":"hello"}'::jsonb, 1, 'test')
                        """,
                        tenant))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a published template version cannot be edited")
    void published_templates_are_immutable() {
        app.update(
                """
                INSERT INTO notification.templates
                       (tenant_id, template_key, channel, locale, version, status, parts, units, published_by)
                VALUES (?, 'immutable.test', 'SMS', 'en', 1, 'PUBLISHED', '{"body":"hello"}'::jsonb, 1, 'test')
                """,
                tenant);

        // A sent message records the version that produced it. Editing that version silently
        // changes what a past message said it said.
        assertThatThrownBy(() -> app.update(
                        "UPDATE notification.templates SET parts = '{\"body\":\"edited\"}'::jsonb"
                                + " WHERE template_key = 'immutable.test'"))
                .hasMessageContaining("immutable");
    }

    @Test
    @DisplayName("a template with no attribution or no measurement cannot be published")
    void publishing_requires_attribution_and_measurement() {
        assertThatThrownBy(() -> app.update(
                        """
                        INSERT INTO notification.templates
                               (tenant_id, template_key, channel, locale, version, status, parts, units)
                        VALUES (?, 'unattributed', 'SMS', 'en', 1, 'PUBLISHED', '{"body":"hi"}'::jsonb, 1)
                        """,
                        tenant))
                .hasMessageContaining("published_is_attributed");

        // Units are how cost reporting works. A published template nobody measured is a template
        // whose bill arrives as a surprise.
        assertThatThrownBy(() -> app.update(
                        """
                        INSERT INTO notification.templates
                               (tenant_id, template_key, channel, locale, version, status, parts, published_by)
                        VALUES (?, 'unmeasured', 'SMS', 'en', 1, 'PUBLISHED', '{"body":"hi"}'::jsonb, 'test')
                        """,
                        tenant))
                .hasMessageContaining("published_is_measured");
    }

    @Test
    @DisplayName("a quiet-hours window with one end is refused")
    void quiet_hours_are_complete_or_absent() {
        assertThatThrownBy(() -> app.update(
                        """
                        INSERT INTO notification.channel_policy (tenant_id, category, channels, quiet_from)
                        VALUES (?, 'MARKETING', ARRAY['SMS'], '21:00')
                        """,
                        tenant))
                .hasMessageContaining("quiet_hours_are_complete");
    }

    @Test
    @DisplayName("one event has exactly one disposition")
    void events_are_deduplicated() {
        // A distinct id per run: the table is a durable dedupe ledger, so a fixed id would make
        // this test pass once and then assert the previous run's row forever.
        long eventId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        app.update(
                """
                INSERT INTO notification.consumed_events
                       (publisher, event_id, tenant_id, event_type, occurred_at, epoch, disposition)
                VALUES ('core', ?, ?, 'transfer.completed', now(), 1, 'NOTIFIED')
                """,
                eventId, tenant);

        // At-least-once is the contract (ADR 0008); this constraint is its price. Note the key is
        // (publisher, event_id) and not tenant-scoped: two publishers may both number an event 1.
        assertThatThrownBy(() -> app.update(
                        """
                        INSERT INTO notification.consumed_events
                               (publisher, event_id, tenant_id, event_type, occurred_at, epoch, disposition)
                        VALUES ('core', ?, ?, 'transfer.completed', now(), 1, 'SUPPRESSED')
                        """,
                        eventId, tenant))
                .hasMessageContaining("one_disposition_per_event");
    }

    @Test
    @DisplayName("one business moment produces one message per channel and recipient")
    void a_moment_notifies_once() {
        UUID recipient = UUID.randomUUID();
        insertNotification("transfer.completed:abc", recipient);

        // The backstop of D-4's second level: it survives a mistake in "one category, one
        // publisher", a redriven topic, and a future second publisher — none of which the
        // event-id key can see, because they are different events about one moment.
        assertThatThrownBy(() -> insertNotification("transfer.completed:abc", recipient))
                .hasMessageContaining("one_message_per_moment");

        // A different recipient for the same moment is a different message, and must be allowed:
        // an intra-tenant transfer owes an alert to both sides.
        assertThat(insertNotification("transfer.completed:abc", UUID.randomUUID())).isEqualTo(1);
    }

    @Test
    @DisplayName("a sent message is terminal")
    void terminal_states_are_terminal() {
        UUID recipient = UUID.randomUUID();
        insertNotification("terminal:test", recipient);
        app.update("UPDATE notification.notifications SET state = 'SENT' WHERE recipient_ref = ?", recipient);

        assertThatThrownBy(() -> app.update(
                        "UPDATE notification.notifications SET state = 'PENDING' WHERE recipient_ref = ?", recipient))
                .hasMessageContaining("terminal");
    }

    @Test
    @DisplayName("delivery attempts cannot be rewritten — by the app role, or by the owner")
    void attempts_are_append_only() {
        UUID recipient = UUID.randomUUID();
        insertNotification("attempts:test", recipient);
        UUID id = app.queryForObject(
                "SELECT id FROM notification.notifications WHERE recipient_ref = ?", UUID.class, recipient);

        app.update(
                """
                INSERT INTO notification.delivery_attempts
                       (tenant_id, notification_id, attempt_no, outcome, client_reference)
                VALUES (?, ?, 1, 'UNKNOWN', 'ref-1')
                """,
                tenant, id);

        // Two protections, and they cover different callers. The app role simply has no UPDATE
        // grant, so it is refused before any trigger runs.
        assertThatThrownBy(() -> app.update(
                        "UPDATE notification.delivery_attempts SET outcome = 'SENT' WHERE notification_id = ?", id))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);

        // The owner holds every grant, so only the trigger stops it — and the owner is the identity
        // that runs migrations and the one an operator reaches for at 2am. History that can be
        // rewritten is not evidence, and evidence is the only reason to keep it.
        JdbcTemplate owner = new JdbcTemplate(ownerDataSource);
        assertThatThrownBy(() -> owner.update(
                        "UPDATE notification.delivery_attempts SET outcome = 'SENT' WHERE notification_id = ?", id))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() ->
                        owner.update("DELETE FROM notification.delivery_attempts WHERE notification_id = ?", id))
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("another tenant's rows are invisible, not merely filtered by the application")
    void tenants_cannot_see_each_other() {
        String moment = "isolation:" + UUID.randomUUID();

        // Everything on one connection, written out rather than leaning on the shared helpers.
        // A pooled JdbcTemplate hands each statement whichever connection is free, so a session
        // SET and the SELECT that depends on it can land on different ones — and a test of tenant
        // isolation that passes or fails on which connection it borrowed is worse than no test.
        // This is the same hazard SET LOCAL exists to prevent in the service itself.
        Integer visible = app.execute((java.sql.Connection connection) -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SET app.tenant_id = '" + tenant + "'");
                statement.executeUpdate(
                        """
                        INSERT INTO notification.notifications
                               (tenant_id, business_moment_key, category, channel, template_key,
                                template_version, locale, recipient_ref, recipient_address, rendered, units)
                        VALUES ('""" + tenant + "', '" + moment + """
                        ', 'TRANSACTIONAL', 'SMS', 'debit.alert', 1, 'en',
                                gen_random_uuid(), 'enc:x', '{"body":"hello"}'::jsonb, 1)
                        """);
                statement.execute("SET app.tenant_id = '" + UUID.randomUUID() + "'");
                try (var rows = statement.executeQuery(
                        "SELECT count(*) FROM notification.notifications WHERE business_moment_key = '"
                                + moment + "'")) {
                    rows.next();
                    return rows.getInt(1);
                }
            }
        });

        assertThat(visible).isZero();
    }

    private int insertNotification(String moment, UUID recipient) {
        return app.update(
                """
                INSERT INTO notification.notifications
                       (tenant_id, business_moment_key, category, channel, template_key, template_version,
                        locale, recipient_ref, recipient_address, rendered, units)
                VALUES (?, ?, 'TRANSACTIONAL', 'SMS', 'debit.alert', 1, 'en', ?, 'enc:x',
                        '{"body":"hello"}'::jsonb, 1)
                """,
                tenant, moment, recipient);
    }
}
