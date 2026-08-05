package org.elyonar.fincore.ledger.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import javax.sql.DataSource;
import org.elyonar.fincore.ledger.support.LedgerPostgresTest;
import org.elyonar.fincore.ledger.support.TenantSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Row-level security actually hides other tenants' rows.
 *
 * <p>This suite exists because the presence check lied. V1 enabled RLS and wrote a policy on every
 * tenant-scoped table, and {@code pg_class.relrowsecurity} duly reported {@code true} — while the
 * ledger, connecting as the tables' owner, still saw every tenant's data. PostgreSQL exempts
 * owners from RLS unless the table is also marked {@code FORCE}. Asserting the switch is on is not
 * the same as asserting the door is locked, so these tests try the door.
 */
@DisplayName("tenant isolation — RLS hides what it claims to hide")
class TenantIsolationTest extends LedgerPostgresTest {

    @Autowired DataSource dataSource;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void seedTwoTenants() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();

        try (TenantSession a = TenantSession.open(dataSource, tenantA)) {
            a.execute(
                    "INSERT INTO currencies VALUES ('NGN',2,'Naira') ON CONFLICT (code) DO NOTHING");
            a.execute(account(), UUID.randomUUID(), tenantA, "a-" + tenantA);
        }
        try (TenantSession b = TenantSession.open(dataSource, tenantB)) {
            b.execute(account(), UUID.randomUUID(), tenantB, "b-" + tenantB);
            b.execute(account(), UUID.randomUUID(), tenantB, "b2-" + tenantB);
        }
    }

    private static String account() {
        return """
               INSERT INTO accounts (id, tenant_id, idempotency_key, type, currency)
               VALUES (?,?,?, 'CUSTOMER','NGN')
               """;
    }

    @Test
    @DisplayName("a tenant sees only its own accounts")
    void a_tenant_sees_only_its_own_rows() {
        try (TenantSession a = TenantSession.open(dataSource, tenantA)) {
            assertThat(a.count("SELECT count(*) FROM accounts")).isEqualTo(1);
            assertThat(a.count("SELECT count(*) FROM accounts WHERE tenant_id = ?", tenantB))
                    .as("another tenant's rows must not be reachable even when named explicitly")
                    .isZero();
        }
        try (TenantSession b = TenantSession.open(dataSource, tenantB)) {
            assertThat(b.count("SELECT count(*) FROM accounts")).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("a connection with no tenant context sees nothing at all")
    void no_context_means_no_rows() {
        try (TenantSession anonymous = TenantSession.openWithoutTenant(dataSource)) {
            assertThat(anonymous.count("SELECT count(*) FROM accounts"))
                    .as("this is the case that failed silently before FORCE ROW LEVEL SECURITY: "
                            + "the owner saw every tenant's rows with no context set")
                    .isZero();
        }
    }

    @Test
    @DisplayName("a tenant cannot write a row belonging to another tenant")
    void cannot_insert_across_tenants() {
        try (TenantSession a = TenantSession.open(dataSource, tenantA)) {
            assertThatThrownBy(() -> a.execute(account(), UUID.randomUUID(), tenantB, "smuggled"))
                    .isInstanceOf(TenantSession.SqlFailure.class)
                    .hasMessageContaining("row-level security");
        }
    }

    @Test
    @DisplayName("the same group_ref in two tenants never sums together")
    void shared_group_ref_does_not_leak() {
        // group_ref is the one identifier not covered by a composite foreign key; the design
        // states its isolation rests on RLS rather than on a key (data-model.md), so that claim
        // needs a test rather than a sentence.
        try (TenantSession a = TenantSession.open(dataSource, tenantA)) {
            a.execute(
                    """
                    INSERT INTO accounts (id, tenant_id, idempotency_key, type, currency, group_ref,
                                          allow_negative)
                    VALUES (?,?,?, 'FEE','NGN','fees-pool', true)
                    """,
                    UUID.randomUUID(), tenantA, "a-pool");
        }
        try (TenantSession b = TenantSession.open(dataSource, tenantB)) {
            b.execute(
                    """
                    INSERT INTO accounts (id, tenant_id, idempotency_key, type, currency, group_ref,
                                          allow_negative)
                    VALUES (?,?,?, 'FEE','NGN','fees-pool', true)
                    """,
                    UUID.randomUUID(), tenantB, "b-pool");

            assertThat(b.count("SELECT count(*) FROM accounts WHERE group_ref = 'fees-pool'"))
                    .as("a group label shared by two tenants must resolve to one tenant's members")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a pooled connection never carries the previous tenant's context")
    void connection_reuse_does_not_leak_tenant_context() {
        // The failure this guards against is silent and survives review: a session-scoped
        // `SET app.tenant_id` returns to the pool still set, and the next borrower — possibly
        // serving a different tenant — inherits it. Closing and reopening here deliberately
        // recycles a physical connection through the pool.
        for (int i = 0; i < 5; i++) {
            try (TenantSession a = TenantSession.open(dataSource, tenantA)) {
                assertThat(a.count("SELECT count(*) FROM accounts")).isEqualTo(1);
            }
            try (TenantSession anonymous = TenantSession.openWithoutTenant(dataSource)) {
                assertThat(anonymous.count("SELECT count(*) FROM accounts"))
                        .as("round %d: a recycled connection must not inherit tenant A's context", i)
                        .isZero();
            }
        }
    }
}
