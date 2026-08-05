package org.elyonar.fincore.ledger.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.elyonar.fincore.ledger.posting.EntryLine.Direction.CREDIT;
import static org.elyonar.fincore.ledger.posting.EntryLine.Direction.DEBIT;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.elyonar.fincore.ledger.shared.ErrorCode;
import org.elyonar.fincore.ledger.shared.LedgerException;
import org.elyonar.fincore.ledger.support.LedgerPostgresTest;
import org.elyonar.fincore.ledger.support.TenantSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("reading a transaction back")
class TransactionReadServiceTest extends LedgerPostgresTest {

    @Autowired PostingService posting;
    @Autowired ReversalService reversals;
    @Autowired TransactionReadService transactions;
    @Autowired DataSource dataSource;

    private UUID tenant;
    private UUID customer;
    private UUID settlement;
    private TenantSession db;

    @BeforeEach
    void seed() {
        tenant = UUID.randomUUID();
        customer = UUID.randomUUID();
        settlement = UUID.randomUUID();
        db = TenantSession.open(dataSource, tenant);
        db.execute("INSERT INTO currencies VALUES ('NGN',2,'Naira') ON CONFLICT (code) DO NOTHING");
        for (UUID id : List.of(customer, settlement)) {
            db.execute(
                    "INSERT INTO accounts (id, tenant_id, idempotency_key, type, currency, allow_negative)"
                            + " VALUES (?,?,?, 'CUSTOMER','NGN', true)",
                    id, tenant, "acct-" + id);
            db.execute("INSERT INTO balances (account_id, tenant_id) VALUES (?,?)", id, tenant);
        }
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private UUID post(String key, long amount) {
        return posting.post(
                        new PostTransactionCommand(
                                tenant, key, "user:ada", "svc:orch", "transfer",
                                List.of(
                                        new EntryLine(settlement, DEBIT, amount, "NGN", null),
                                        new EntryLine(customer, CREDIT, amount, "NGN", null))))
                .transactionId();
    }

    @Test
    @DisplayName("a transaction comes back with its entries and attribution")
    void reads_a_transaction_with_entries() {
        UUID id = post("tx-1", 500_00);

        var t = transactions.find(tenant, id);

        assertThat(t.id()).isEqualTo(id);
        assertThat(t.status()).isEqualTo("POSTED");
        assertThat(t.idempotencyKey()).isEqualTo("tx-1");
        assertThat(t.initiatedBy()).as("who asked").isEqualTo("user:ada");
        assertThat(t.executedBy()).as("which system acted").isEqualTo("svc:orch");
        assertThat(t.entries()).hasSize(2);
        assertThat(t.entries()).allSatisfy(e -> assertThat(e.amountMinor()).isEqualTo(500_00));
        assertThat(t.entries()).extracting(TransactionReadService.EntryView::direction)
                .containsExactlyInAnyOrder("DEBIT", "CREDIT");
    }

    @Test
    @DisplayName("a reversed original reports its status, and the reversal names its target")
    void reversal_links_are_visible() {
        UUID original = post("tx-1", 500_00);
        UUID reversal =
                reversals
                        .reverse(new ReverseTransactionCommand(tenant, original, "rev-1", "user:ops", "svc:orch"))
                        .transactionId();

        assertThat(transactions.find(tenant, original).status()).isEqualTo("REVERSED");
        assertThat(transactions.find(tenant, reversal).reversesTransactionId())
                .as("the record shows what undid what")
                .isEqualTo(original);
    }

    @Test
    @DisplayName("another tenant's transaction is not found")
    void cross_tenant_reads_are_not_found() {
        UUID id = post("tx-1", 500_00);

        assertThatThrownBy(() -> transactions.find(UUID.randomUUID(), id))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).errorCode())
                .as("indistinguishable from a transaction that does not exist")
                .isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
    }

    @Test
    @DisplayName("an unknown id is not found")
    void unknown_transaction_is_not_found() {
        assertThatThrownBy(() -> transactions.find(tenant, UUID.randomUUID()))
                .isInstanceOf(LedgerException.class);
    }
}
