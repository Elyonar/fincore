package org.elyonar.fincore.identity;

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
import org.springframework.test.context.ActiveProfiles;

/**
 * Every object the design depends on exists, and every rule it claims fires. Modelled on the
 * ledger's and Notification's schema suites: a trigger that stopped firing looks exactly like one
 * that never fired, and only a test that tries to break the rule tells them apart.
 *
 * <p>Two identities, for the reason Notification's suite records: a grant and a trigger protect
 * different callers. The app role has no UPDATE on {@code auth_events}, so a test using it proves
 * the grant and says nothing about the trigger — and the trigger is what protects the owner, the
 * one identity the grant cannot.
 */
@SpringBootTest
@ActiveProfiles("test") // sanctions KeyRing's ephemeral dev key so the context loads without a real key
@DisplayName("schema — the rules live in the database, and they fire")
class SchemaTest {

    @Autowired @Qualifier("appJdbcTemplate") private JdbcTemplate app;

    @Autowired
    @Qualifier("dataSource")
    private javax.sql.DataSource ownerDataSource;

    private JdbcTemplate owner;
    private UUID tenant;

    @BeforeEach
    void scopeToATenant() {
        owner = new JdbcTemplate(ownerDataSource);
        tenant = UUID.randomUUID();
        owner.update("INSERT INTO identity.tenants (id, name, created_by) VALUES (?, 't', 'test')", tenant);
        app.execute("SET app.tenant_id = '" + tenant + "'");
    }

    @Test
    @DisplayName("every designed table exists")
    void tablesExist() {
        List<String> tables = app.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'identity'"
                        + " AND table_name <> 'flyway_schema_history' ORDER BY table_name",
                String.class);
        assertThat(tables)
                .containsExactly(
                        "auth_events",
                        "credentials",
                        "login_throttle",
                        "refresh_families",
                        "refresh_tokens",
                        "role_permissions",
                        "service_clients",
                        "tenants",
                        "user_roles",
                        "user_units",
                        "users");
    }

    @Test
    @DisplayName("row-level security is enabled and forced on every tenant-scoped table")
    void rlsForced() {
        List<String> unforced = app.queryForList(
                "SELECT relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE n.nspname = 'identity' AND c.relkind = 'r'"
                        + " AND relname IN ('users','credentials','role_permissions','user_roles',"
                        + "   'user_units','refresh_families','refresh_tokens','login_throttle','auth_events')"
                        + " AND NOT (relrowsecurity AND relforcerowsecurity)",
                String.class);
        assertThat(unforced).isEmpty();
    }

    @Test
    @DisplayName("there is deliberately no signing_keys table — keys never touch the database")
    void noSigningKeysTable() {
        Integer count = app.queryForObject(
                "SELECT count(*) FROM information_schema.tables"
                        + " WHERE table_schema = 'identity' AND table_name = 'signing_keys'",
                Integer.class);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("auth_events refuses UPDATE and DELETE — the audit trail is append-only")
    void authEventsAppendOnly() {
        // Owner, with the same tenant context, so RLS admits the row and the trigger is what stops
        // the mutation rather than a missing grant or an invisible row.
        owner.update("SELECT set_config('app.tenant_id', ?, false)", tenant.toString());
        owner.update(
                "INSERT INTO identity.auth_events (tenant_id, event, source) VALUES (?, 'TEST', 'test')",
                tenant);
        assertThatThrownBy(() -> owner.update("UPDATE identity.auth_events SET event = 'TAMPERED'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> owner.update("DELETE FROM identity.auth_events"))
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("a username is unique per tenant, case-insensitively")
    void usernameUniquePerTenant() {
        app.update(
                "INSERT INTO identity.users (tenant_id, id, username, email, first_name, last_name,"
                        + " created_by, created_via) VALUES (?,?,?,?,?,?,?,?)",
                tenant, UUID.randomUUID(), "Ada", "a@x", "A", "O", "test", "test");
        assertThatThrownBy(() -> app.update(
                        "INSERT INTO identity.users (tenant_id, id, username, email, first_name,"
                                + " last_name, created_by, created_via) VALUES (?,?,?,?,?,?,?,?)",
                        tenant, UUID.randomUUID(), "ADA", "b@x", "B", "O", "test", "test"))
                .hasMessageContaining("users_username_per_tenant");
    }
}
