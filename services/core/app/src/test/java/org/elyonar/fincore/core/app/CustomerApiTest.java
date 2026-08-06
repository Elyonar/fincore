package org.elyonar.fincore.core.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Customer's administrative surface, over real HTTP.
 *
 * <p>Until this file existed, every customer on the platform was created by a test reaching into
 * {@code customer.customers} with an INSERT. That worked, and it meant the four endpoints
 * {@code api.md} had promised since v1.0 were never exercised, never wrong, and never there.
 *
 * <p>The assertion that matters most is {@code a_tier_change_is_recorded_with_who_and_why}. A KYC
 * tier is the ceiling on what someone may move, so a tier change is a limit change; if it can
 * happen without a trail, the limit is advisory.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CustomerApiTest {

    @LocalServerPort private int port;
    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired @Qualifier("customerJdbcTemplate") private JdbcTemplate customerDb;
    @Autowired @Qualifier("customerTransactionManager") private PlatformTransactionManager customerTx;

    private UUID tenantId;

    @DynamicPropertySource
    static void quiet(DynamicPropertyRegistry registry) {
        registry.add("fincore.core.worker.interval-ms", () -> "3600000");
        registry.add("fincore.core.outbox.relay.interval-ms", () -> "3600000");
    }

    @BeforeEach
    void freshTenant() {
        tenantId = UUID.randomUUID();
    }

    // ------------------------------------------------------------------ harness

    private HttpRequest.Builder as(String path, String permissions, String principal) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Dev-Tenant-Id", tenantId.toString())
                .header("X-Dev-Principal", principal)
                .header("X-Dev-Permissions", permissions);
    }

    private HttpRequest.Builder as(String path, String permissions) {
        return as(path, permissions, "user:admin");
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String field(String json, String name) {
        int at = json.indexOf("\"" + name + "\":\"");
        if (at < 0) {
            return null;
        }
        at += name.length() + 4;
        return json.substring(at, json.indexOf('"', at));
    }

    private HttpResponse<String> createCustomer(String externalRef) {
        return send(
                as("/v1/customers", "customers:create")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        "{\"externalRef\":\"" + externalRef
                                                + "\",\"fullName\":\"Ada Lovelace\",\"phone\":\"+2348000000000\"}"))
                        .build());
    }

    // ------------------------------------------------------------------ creation

    @Test
    void a_customer_can_be_created_and_read_back() {
        HttpResponse<String> created = createCustomer("CUST-" + UUID.randomUUID());

        assertThat(created.statusCode()).isEqualTo(201);
        String id = field(created.body(), "customerId");
        assertThat(id).isNotNull();
        // Absent from the request, so it came from the default rather than the caller — the safe
        // direction, since defaulting a tier upward would hand out limits by accident.
        assertThat(created.body()).contains("\"kycTier\":\"TIER_1\"").contains("\"status\":\"ACTIVE\"");

        HttpResponse<String> read = send(as("/v1/customers/" + id, "customers:read").GET().build());
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.body()).contains("Ada Lovelace").contains("\"accounts\":[]");
    }

    @Test
    void the_tenants_own_customer_number_cannot_be_used_twice() {
        String ref = "CUST-" + UUID.randomUUID();
        assertThat(createCustomer(ref).statusCode()).isEqualTo(201);

        HttpResponse<String> again = createCustomer(ref);

        // Two records for one person is how a tier gets enforced against the wrong one.
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.body()).contains("EXTERNAL_REF_TAKEN");
    }

    @Test
    void the_same_customer_number_in_another_tenant_is_fine() {
        String ref = "CUST-" + UUID.randomUUID();
        assertThat(createCustomer(ref).statusCode()).isEqualTo(201);

        tenantId = UUID.randomUUID(); // a different bank, numbering its own customers
        assertThat(createCustomer(ref).statusCode()).isEqualTo(201);
    }

    // ------------------------------------------------------------------ reading

    @Test
    void a_customer_of_another_tenant_is_indistinguishable_from_one_that_does_not_exist() {
        String id = field(createCustomer("CUST-" + UUID.randomUUID()).body(), "customerId");

        tenantId = UUID.randomUUID();
        HttpResponse<String> read = send(as("/v1/customers/" + id, "customers:read").GET().build());

        // 404 and not 403: a 403 would confirm the customer exists somewhere, which is exactly what
        // row-level security spent the query hiding.
        assertThat(read.statusCode()).isEqualTo(404);
        assertThat(read.body()).contains("CUSTOMER_NOT_FOUND");
    }

    @Test
    void an_unknown_customer_is_a_404() {
        assertThat(send(as("/v1/customers/" + UUID.randomUUID(), "customers:read").GET().build()).statusCode())
                .isEqualTo(404);
    }

    // ------------------------------------------------------------------ tier

    @Test
    void a_tier_change_is_recorded_with_who_and_why() {
        String id = field(createCustomer("CUST-" + UUID.randomUUID()).body(), "customerId");

        HttpResponse<String> changed =
                send(
                        as("/v1/customers/" + id + "/tier", "customers:tier", "user:compliance")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"toTier\":\"TIER_3\",\"reason\":\"passport verified\"}"))
                                .build());

        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(changed.body()).contains("\"fromTier\":\"TIER_1\"").contains("\"toTier\":\"TIER_3\"");

        String trail =
                new TransactionTemplate(customerTx)
                        .execute(
                                s -> {
                                    customerDb.queryForObject(
                                            "SELECT set_config('app.tenant_id', ?, true)",
                                            String.class, tenantId.toString());
                                    return customerDb.queryForObject(
                                            """
                                            SELECT from_tier || '|' || to_tier || '|' || reason || '|' || changed_by
                                              FROM customer.customer_tier_changes WHERE customer_id = ?::uuid
                                            """,
                                            String.class, id);
                                });

        assertThat(trail).isEqualTo("TIER_1|TIER_3|passport verified|user:compliance");
    }

    @Test
    void a_tier_change_without_a_reason_is_refused() {
        String id = field(createCustomer("CUST-" + UUID.randomUUID()).body(), "customerId");

        HttpResponse<String> changed =
                send(
                        as("/v1/customers/" + id + "/tier", "customers:tier")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"toTier\":\"TIER_2\"}"))
                                .build());

        // The trail answers "what" without it; "why" is the question actually asked afterwards.
        assertThat(changed.statusCode()).isEqualTo(422);
        assertThat(changed.body()).contains("REASON_REQUIRED");
    }

    @Test
    void changing_a_tier_to_the_one_it_already_has_is_refused() {
        String id = field(createCustomer("CUST-" + UUID.randomUUID()).body(), "customerId");

        HttpResponse<String> changed =
                send(
                        as("/v1/customers/" + id + "/tier", "customers:tier")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"toTier\":\"TIER_1\",\"reason\":\"no change\"}"))
                                .build());

        assertThat(changed.statusCode()).isEqualTo(422);
        assertThat(changed.body()).contains("TIER_UNCHANGED");
    }

    @Test
    void the_tier_history_cannot_be_rewritten() {
        String id = field(createCustomer("CUST-" + UUID.randomUUID()).body(), "customerId");
        send(
                as("/v1/customers/" + id + "/tier", "customers:tier", "user:compliance")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"toTier\":\"TIER_2\",\"reason\":\"kyc\"}"))
                        .build());

        // Enforced in the database, not the service: an audit trail that application code is
        // trusted not to rewrite is one deployment away from being wrong.
        assertThat(
                        org.assertj.core.api.Assertions.catchThrowable(
                                () ->
                                        new TransactionTemplate(customerTx)
                                                .executeWithoutResult(
                                                        s -> {
                                                            customerDb.queryForObject(
                                                                    "SELECT set_config('app.tenant_id', ?, true)",
                                                                    String.class, tenantId.toString());
                                                            customerDb.update(
                                                                    "UPDATE customer.customer_tier_changes"
                                                                            + " SET reason = 'rewritten'"
                                                                            + " WHERE customer_id = ?::uuid",
                                                                    id);
                                                        })))
                .hasMessageContaining("append-only");
    }

    // ------------------------------------------------------------------ accounts

    @Test
    void a_ledger_account_can_be_linked_and_then_appears_on_the_profile() {
        String id = field(createCustomer("CUST-" + UUID.randomUUID()).body(), "customerId");
        UUID account = UUID.randomUUID();

        HttpResponse<String> linked =
                send(
                        as("/v1/customers/" + id + "/accounts", "customers:link")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"ledgerAccountId\":\"" + account + "\",\"currency\":\"NGN\"}"))
                                .build());

        assertThat(linked.statusCode()).isEqualTo(201);
        assertThat(linked.body()).contains("\"role\":\"PRIMARY\"");

        HttpResponse<String> read = send(as("/v1/customers/" + id, "customers:read").GET().build());
        assertThat(read.body()).contains(account.toString());
    }

    @Test
    void one_ledger_account_cannot_be_held_by_two_customers() {
        String first = field(createCustomer("CUST-" + UUID.randomUUID()).body(), "customerId");
        String second = field(createCustomer("CUST-" + UUID.randomUUID()).body(), "customerId");
        UUID account = UUID.randomUUID();
        String body = "{\"ledgerAccountId\":\"" + account + "\",\"currency\":\"NGN\"}";

        assertThat(
                        send(
                                        as("/v1/customers/" + first + "/accounts", "customers:link")
                                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                                .build())
                                .statusCode())
                .isEqualTo(201);

        HttpResponse<String> clash =
                send(
                        as("/v1/customers/" + second + "/accounts", "customers:link")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build());

        // Two holders would make CustomerEligibility.holdsAccount unanswerable, and the money path
        // asks it on every transfer.
        assertThat(clash.statusCode()).isEqualTo(409);
        assertThat(clash.body()).contains("ACCOUNT_ALREADY_HELD");
    }

    @Test
    void linking_to_an_unknown_customer_is_a_404() {
        HttpResponse<String> linked =
                send(
                        as("/v1/customers/" + UUID.randomUUID() + "/accounts", "customers:link")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"ledgerAccountId\":\"" + UUID.randomUUID()
                                                        + "\",\"currency\":\"NGN\"}"))
                                .build());

        assertThat(linked.statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ permissions

    @Test
    void every_endpoint_denies_by_default() {
        String id = field(createCustomer("CUST-" + UUID.randomUUID()).body(), "customerId");
        // A permission that exists but is the wrong one — the interesting case, because a caller
        // holding *some* token is the realistic threat, not one holding none.
        String wrong = "transfers:create";

        assertThat(send(as("/v1/customers", wrong).POST(HttpRequest.BodyPublishers.ofString("{}")).build())
                        .statusCode())
                .isEqualTo(403);
        assertThat(send(as("/v1/customers/" + id, wrong).GET().build()).statusCode()).isEqualTo(403);
        assertThat(
                        send(
                                        as("/v1/customers/" + id + "/tier", wrong)
                                                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                                .build())
                                .statusCode())
                .isEqualTo(403);
        assertThat(
                        send(
                                        as("/v1/customers/" + id + "/accounts", wrong)
                                                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                                .build())
                                .statusCode())
                .isEqualTo(403);
    }

    @Test
    void an_unauthenticated_caller_gets_401_not_404() {
        HttpResponse<String> response =
                send(
                        HttpRequest.newBuilder(
                                        URI.create("http://localhost:" + port + "/v1/customers/" + UUID.randomUUID()))
                                .GET()
                                .build());

        assertThat(response.statusCode()).isEqualTo(401);
    }
}
