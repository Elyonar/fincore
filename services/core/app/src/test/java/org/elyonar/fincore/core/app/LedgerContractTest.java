package org.elyonar.fincore.core.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.core.orchestration.api.LedgerOutcome;
import org.elyonar.fincore.core.orchestration.api.LedgerPosting;
import org.elyonar.fincore.core.orchestration.internal.ledger.HttpLedgerClient;
import org.elyonar.fincore.core.orchestration.internal.ledger.LedgerClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Core's ledger client against the <strong>real</strong> Ledger.
 *
 * <p>Every other test in this module runs against a stub, which proves what Core does with an
 * answer but not that the answer is the one the Ledger actually gives. This suite is the joint
 * contract: our request shape against their validation, our classification against their status
 * codes and error codes. A mismatch here is invisible to both services' own suites — each is
 * internally consistent and wrong about the other.
 *
 * <p><strong>Requires a running Ledger</strong>, so it is tagged and excluded from the default
 * build rather than silently skipped. A test that quietly passes when its subject is absent is
 * worse than one that is not run, because the report says the contract was checked.
 *
 * <pre>
 *   docker compose up -d ledger
 *   ./mvnw -pl services/core/app -am -Pcontract test
 * </pre>
 */
@Tag("contract")
class LedgerContractTest {

    private static final String LEDGER =
            System.getProperty("fincore.contract.ledger-url", "http://localhost:58080");

    private static final HttpClient http = HttpClient.newHttpClient();
    private static final LedgerClient client =
            new HttpLedgerClient(
                    LEDGER,
                    java.time.Duration.ofSeconds(2),
                    java.time.Duration.ofSeconds(10),
                    JsonMapper.builder().build());

    private static UUID tenantId;
    private static UUID funding;
    private static UUID customer;
    private static UUID fees;

    @BeforeAll
    static void provisionThroughTheRealLedger() {
        tenantId = firstActiveTenant();
        // An internal account may go negative, so it can fund the others without being funded
        // itself — the Ledger's own mechanism rather than a fixture reaching into its tables.
        funding = createAccount("INTERNAL", true);
        customer = createAccount("CUSTOMER", false);
        fees = createAccount("FEE", true);
    }

    private static UUID firstActiveTenant() {
        // The Ledger requires a registered tenant: row-level security isolates tenants but has
        // nothing to say about whether one is real. Provisioned by db/init in development.
        String raw = System.getProperty("fincore.contract.tenant-id", "");
        if (!raw.isBlank()) {
            return UUID.fromString(raw);
        }
        throw new IllegalStateException(
                "set -Dfincore.contract.tenant-id to a tenant registered in the Ledger");
    }

    private static UUID createAccount(String type, boolean allowNegative) {
        String body =
                """
                {"idempotencyKey":"contract-%s","type":"%s","currency":"NGN",
                 "allowNegative":%s,"customerRef":"contract-test"}
                """
                        .formatted(UUID.randomUUID(), type, allowNegative);
        HttpResponse<String> response =
                send(
                        HttpRequest.newBuilder(URI.create(LEDGER + "/v1/accounts"))
                                .header("Content-Type", "application/json")
                                .header("X-Tenant-Id", tenantId.toString())
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build());
        assertThat(response.statusCode()).isIn(200, 201);
        String json = response.body();
        int at = json.indexOf("\"accountId\":\"") + 13;
        return UUID.fromString(json.substring(at, json.indexOf('"', at)));
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("the Ledger at " + LEDGER + " is not reachable", e);
        }
    }

    private static LedgerPosting posting(String key, long amountMinor, UUID from, UUID to) {
        return new LedgerPosting(
                key,
                "user:contract-test",
                "contract",
                List.of(
                        new LedgerPosting.Entry(from, LedgerPosting.Direction.DEBIT, amountMinor, "NGN"),
                        new LedgerPosting.Entry(to, LedgerPosting.Direction.CREDIT, amountMinor, "NGN")));
    }

    // ------------------------------------------------------------------ the shape

    @Test
    void our_request_shape_is_one_the_ledger_accepts() {
        // The single most valuable assertion here: field names, the decimal-string amounts, and
        // the entry structure are all things a stub would happily accept while the real service
        // rejected them.
        LedgerOutcome outcome =
                client.post(tenantId, posting("core:" + UUID.randomUUID() + ":post", 100_000, funding, customer));

        assertThat(outcome).isInstanceOf(LedgerOutcome.Success.class);
    }

    @Test
    void a_three_entry_posting_with_a_fee_is_accepted() {
        // The shape every fee-bearing transfer uses: principal and fee as entries of one
        // transaction, balanced per currency.
        LedgerOutcome outcome =
                client.post(
                        tenantId,
                        new LedgerPosting(
                                "core:" + UUID.randomUUID() + ":post",
                                "user:contract-test",
                                "contract with fee",
                                List.of(
                                        new LedgerPosting.Entry(funding, LedgerPosting.Direction.DEBIT, 102_000, "NGN"),
                                        new LedgerPosting.Entry(customer, LedgerPosting.Direction.CREDIT, 100_000, "NGN"),
                                        new LedgerPosting.Entry(fees, LedgerPosting.Direction.CREDIT, 2_000, "NGN"))));

        assertThat(outcome).isInstanceOf(LedgerOutcome.Success.class);
    }

    // --------------------------------------------------------------- idempotency

    @Test
    void replaying_a_key_returns_the_same_transaction() {
        // The property the whole retry rule rests on. If the Ledger did not replay, every UNKNOWN
        // our worker retries would double-post.
        String key = "core:" + UUID.randomUUID() + ":post";

        LedgerOutcome first = client.post(tenantId, posting(key, 50_000, funding, customer));
        LedgerOutcome second = client.post(tenantId, posting(key, 50_000, funding, customer));

        assertThat(first).isInstanceOf(LedgerOutcome.Success.class);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void the_same_key_with_different_money_is_refused_as_our_bug() {
        String key = "core:" + UUID.randomUUID() + ":post";
        client.post(tenantId, posting(key, 50_000, funding, customer));

        LedgerOutcome reused = client.post(tenantId, posting(key, 60_000, funding, customer));

        assertThat(reused).isInstanceOf(LedgerOutcome.DefiniteFailure.class);
        // Classified as ours, not as a business refusal: minting a new key to route around it is
        // exactly how a double post happens.
        assertThat(((LedgerOutcome.DefiniteFailure) reused).callerBug()).isTrue();
    }

    // ------------------------------------------------------------------ refusals

    @Test
    void a_guarded_account_without_funds_is_a_definite_failure() {
        // The customer account is allow_negative = false and has only what the tests credited it,
        // so a large debit is refused — and must classify as terminal rather than unknown, or the
        // saga would retry a rejection forever.
        LedgerOutcome outcome =
                client.post(
                        tenantId,
                        posting("core:" + UUID.randomUUID() + ":post", 999_999_999L, customer, funding));

        assertThat(outcome).isInstanceOf(LedgerOutcome.DefiniteFailure.class);
        assertThat(((LedgerOutcome.DefiniteFailure) outcome).callerBug()).isFalse();
    }

    @Test
    void an_account_that_does_not_exist_is_a_definite_failure() {
        LedgerOutcome outcome =
                client.post(
                        tenantId,
                        posting("core:" + UUID.randomUUID() + ":post", 1_000, funding, UUID.randomUUID()));

        assertThat(outcome).isInstanceOf(LedgerOutcome.DefiniteFailure.class);
    }

    // ------------------------------------------------------------------ reversal

    @Test
    void a_reversal_succeeds_and_a_second_one_converges_on_the_winner() {
        String key = "core:" + UUID.randomUUID() + ":post";
        LedgerOutcome posted = client.post(tenantId, posting(key, 25_000, funding, customer));
        UUID transactionId = ((LedgerOutcome.Success) posted).ledgerTransactionId();

        LedgerOutcome first =
                client.reverse(tenantId, transactionId, "core:" + UUID.randomUUID() + ":reverse", "user:contract-test");
        assertThat(first).isInstanceOf(LedgerOutcome.Success.class);

        // A different key, so this is not an idempotent replay — it is a genuine second attempt at
        // reversing an already-reversed transaction. It must settle rather than retry-loop.
        LedgerOutcome second =
                client.reverse(tenantId, transactionId, "core:" + UUID.randomUUID() + ":reverse", "user:contract-test");

        assertThat(second).isInstanceOf(LedgerOutcome.Success.class);
        assertThat(((LedgerOutcome.Success) second).ledgerTransactionId())
                .isEqualTo(((LedgerOutcome.Success) first).ledgerTransactionId());
    }
}
