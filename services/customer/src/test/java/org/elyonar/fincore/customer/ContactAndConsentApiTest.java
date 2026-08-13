package org.elyonar.fincore.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.elyonar.fincore.customer.internal.TenantRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Who to contact about an account, and what they agreed to.
 *
 * <p>The lookup exists for a caller that holds an account id and nothing else, which is every
 * consumer of a domain event: payloads carry no PII by design (ADR 0008), so a service that sends
 * to customers must ask on every send. It is the one query in this module that runs from an account
 * to a customer rather than the other way round.
 *
 * <p>Two properties here are load-bearing beyond "it returns data". The response carries **no name
 * and no tier**, so a machine that sends messages can hold exactly the grant it needs; and consent
 * is per category and channel, because "accepts SMS alerts, refuses marketing, never asked about
 * email" is one customer and three different answers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("contact and consent — what a sender may ask, and no more")
class ContactAndConsentApiTest {

    // Every tenant a test uses must be registered, because Core now refuses one it has
    // never heard of. Registering here rather than weakening the gate for tests: a guard
    // switched off under test is a guard nobody has tested.
    @Autowired private TenantRegistry tenantRegistry;

    @LocalServerPort private int port;
    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired @Qualifier("customerJdbcTemplate") private JdbcTemplate customerDb;

    private UUID tenantId;

    @DynamicPropertySource
    static void quiet(DynamicPropertyRegistry registry) {
        registry.add("fincore.core.worker.interval-ms", () -> "3600000");
        registry.add("fincore.core.outbox.relay.interval-ms", () -> "3600000");
    }

    @BeforeEach
    void freshTenant() {
        tenantId = UUID.randomUUID();
        tenantRegistry.register(tenantId, "test tenant", "test");
    }

    // ------------------------------------------------------------------ harness

    private HttpRequest.Builder as(String path, String permissions) {
        return as(path, permissions, tenantId);
    }

    private HttpRequest.Builder as(String path, String permissions, UUID tenant) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Dev-Tenant-Id", tenant.toString())
                .header("X-Dev-Principal", "user:admin")
                .header("X-Dev-Permissions", permissions);
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

    /** A customer with both address kinds, holding one ledger account. */
    private record Fixture(String customerId, UUID accountId) {}

    private Fixture customerHoldingAnAccount(String phone, String email) {
        return customerHoldingAnAccount(phone, email, null);
    }

    private Fixture customerHoldingAnAccount(String phone, String email, String locale) {
        String body = "{\"externalRef\":\"CUST-" + UUID.randomUUID() + "\",\"fullName\":\"Ada Lovelace\""
                + (phone == null ? "" : ",\"phone\":\"" + phone + "\"")
                + (email == null ? "" : ",\"email\":\"" + email + "\"")
                + (locale == null ? "" : ",\"locale\":\"" + locale + "\"")
                + "}";
        HttpResponse<String> created =
                send(as("/v1/customers", "customers:create").POST(HttpRequest.BodyPublishers.ofString(body)).build());
        assertThat(created.statusCode()).isEqualTo(201);
        String customerId = field(created.body(), "customerId");

        UUID accountId = UUID.randomUUID();
        HttpResponse<String> linked = send(as("/v1/customers/" + customerId + "/accounts", "customers:link")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"ledgerAccountId\":\"" + accountId + "\",\"currency\":\"NGN\",\"productCode\":\"P\"}"))
                .build());
        assertThat(linked.statusCode()).isEqualTo(201);

        return new Fixture(customerId, accountId);
    }

    private HttpResponse<String> lookup(UUID accountId) {
        return send(as("/v1/customers/by-account/" + accountId, "customers:contact").GET().build());
    }

    private HttpResponse<String> recordConsent(String customerId, String category, String channel, boolean granted) {
        return send(as("/v1/customers/" + customerId + "/consent", "customers:consent")
                .POST(HttpRequest.BodyPublishers.ofString("{\"category\":\"" + category + "\",\"channel\":\""
                        + channel + "\",\"granted\":" + granted + "}"))
                .build());
    }

    // ------------------------------------------------------------------ the lookup

    @Test
    @DisplayName("an account id resolves to its holder's addresses, keyed by address kind")
    void addresses_are_keyed_by_kind() {
        Fixture fixture = customerHoldingAnAccount("+2348000000000", "ada@example.test");

        HttpResponse<String> found = lookup(fixture.accountId());

        assertThat(found.statusCode()).isEqualTo(200);
        assertThat(found.body())
                .contains("\"customerId\":\"" + fixture.customerId() + "\"")
                // Keyed by kind, not by channel: SMS and WhatsApp are both PHONE, so a new channel
                // on an existing kind needs nothing from Customer at all.
                .contains("\"PHONE\":\"+2348000000000\"")
                .contains("\"EMAIL\":\"ada@example.test\"");
    }

    @Test
    @DisplayName("it returns no name and no tier — a sender gets what it needs and nothing else")
    void the_response_is_narrow() {
        Fixture fixture = customerHoldingAnAccount("+2348000000001", "grace@example.test");

        String body = lookup(fixture.accountId()).body();

        assertThat(body)
                .as("this grant is held by a machine that sends messages; a name is not its business")
                .doesNotContain("Ada Lovelace")
                .doesNotContain("kycTier")
                .doesNotContain("externalRef");
    }

    @Test
    @DisplayName("an address the customer does not have is absent, never null")
    void missing_addresses_are_absent() {
        Fixture fixture = customerHoldingAnAccount("+2348000000002", null);

        String body = lookup(fixture.accountId()).body();

        assertThat(body).contains("\"PHONE\"");
        // A caller iterating the map sees only addresses that exist. An entry it must null-check is
        // an entry that eventually is not checked.
        assertThat(body).doesNotContain("\"EMAIL\"");
    }

    @Test
    @DisplayName("an unknown account is 404, and so is another tenant's")
    void unknown_and_foreign_accounts_are_indistinguishable() {
        Fixture fixture = customerHoldingAnAccount("+2348000000003", null);

        assertThat(lookup(UUID.randomUUID()).statusCode()).isEqualTo(404);

        UUID otherTenant = UUID.randomUUID();
        HttpResponse<String> foreign = send(
                as("/v1/customers/by-account/" + fixture.accountId(), "customers:contact", otherTenant)
                        .GET()
                        .build());
        // Not 403: distinguishing "not yours" from "does not exist" confirms the account exists
        // somewhere, which is the thing tenant isolation is for.
        assertThat(foreign.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("an unlinked account stops resolving")
    void unlinking_ends_the_lookup() {
        Fixture fixture = customerHoldingAnAccount("+2348000000004", null);
        assertThat(lookup(fixture.accountId()).statusCode()).isEqualTo(200);

        customerDb.execute("SET app.tenant_id = '" + tenantId + "'");
        customerDb.update(
                "UPDATE customer.customer_accounts SET unlinked_at = now() WHERE ledger_account_id = ?",
                fixture.accountId());

        assertThat(lookup(fixture.accountId()).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("the lookup denies by default")
    void the_lookup_needs_its_own_permission() {
        Fixture fixture = customerHoldingAnAccount("+2348000000005", null);

        HttpResponse<String> wrongGrant =
                send(as("/v1/customers/by-account/" + fixture.accountId(), "customers:read").GET().build());

        // customers:read is the administrative grant. This endpoint returns PII to a machine, so it
        // carries its own — otherwise "let the notifier read contacts" means "let it read everything".
        assertThat(wrongGrant.statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("the customer's language reaches the lookup, and absence stays absent")
    void locale_is_carried_and_never_invented() {
        Fixture speaksYoruba = customerHoldingAnAccount("+2348000000020", null, "yo");
        Fixture neverAsked = customerHoldingAnAccount("+2348000000021", null);

        assertThat(lookup(speaksYoruba.accountId()).body()).contains("\"locale\":\"yo\"");
        // Null, not "en". What to do about a customer nobody asked is the sending service's policy —
        // a default stored here would make a guess look like the customer's answer, exactly as with
        // consent.
        assertThat(lookup(neverAsked.accountId()).body()).contains("\"locale\":null");
    }

    // ------------------------------------------------------------------ consent

    @Test
    @DisplayName("consent is recorded per category and channel, and appears in the lookup")
    void consent_is_per_category_and_channel() {
        Fixture fixture = customerHoldingAnAccount("+2348000000006", "ada@example.test");

        assertThat(recordConsent(fixture.customerId(), "MARKETING", "SMS", true).statusCode()).isEqualTo(200);
        assertThat(recordConsent(fixture.customerId(), "MARKETING", "EMAIL", false).statusCode()).isEqualTo(200);

        String body = lookup(fixture.accountId()).body();

        // One customer, two answers. A single flag would collapse them, and the collapse always
        // resolves in the direction that sends.
        assertThat(body).contains("\"category\":\"MARKETING\",\"channel\":\"EMAIL\",\"granted\":false");
        assertThat(body).contains("\"category\":\"MARKETING\",\"channel\":\"SMS\",\"granted\":true");
    }

    @Test
    @DisplayName("a customer never asked has no entry — absence is not denial")
    void unset_is_not_denied() {
        Fixture fixture = customerHoldingAnAccount("+2348000000007", null);

        assertThat(lookup(fixture.accountId()).body()).contains("\"consent\":[]");
    }

    @Test
    @DisplayName("every change is kept, with who recorded it and what it changed from")
    void the_history_records_the_transition() {
        Fixture fixture = customerHoldingAnAccount("+2348000000008", null);

        recordConsent(fixture.customerId(), "SERVICE", "SMS", true);
        recordConsent(fixture.customerId(), "SERVICE", "SMS", false);

        customerDb.execute("SET app.tenant_id = '" + tenantId + "'");
        var history = customerDb.queryForList(
                "SELECT from_state, to_state, recorded_by FROM customer.consent_changes"
                        + " WHERE customer_id = ?::uuid ORDER BY recorded_at",
                fixture.customerId());

        assertThat(history).hasSize(2);
        // UNSET → GRANTED → DENIED. "When did they agree" is the question NDPR asks, and a
        // current-state row alone cannot answer it.
        assertThat(history.get(0)).containsEntry("from_state", "UNSET").containsEntry("to_state", "GRANTED");
        assertThat(history.get(1)).containsEntry("from_state", "GRANTED").containsEntry("to_state", "DENIED");
        assertThat(history.get(0).get("recorded_by")).isEqualTo("user:admin");
    }

    @Test
    @DisplayName("consent history cannot be edited")
    void the_history_is_append_only() {
        Fixture fixture = customerHoldingAnAccount("+2348000000009", null);
        recordConsent(fixture.customerId(), "MARKETING", "SMS", true);

        customerDb.execute("SET app.tenant_id = '" + tenantId + "'");

        // A history that can be rewritten is not evidence, and evidence is the only reason to keep
        // one. Enforced by trigger rather than by nobody having written the UPDATE yet.
        assertThatThrownBy(() -> customerDb.update(
                        "UPDATE customer.consent_changes SET to_state = 'DENIED' WHERE customer_id = ?::uuid",
                        fixture.customerId()))
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> customerDb.update(
                        "DELETE FROM customer.consent_changes WHERE customer_id = ?::uuid", fixture.customerId()))
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("an incomplete consent answer is rejected, never read as denial")
    void an_absent_answer_is_not_a_denial() {
        Fixture fixture = customerHoldingAnAccount("+2348000000010", null);

        HttpResponse<String> incomplete = send(as("/v1/customers/" + fixture.customerId() + "/consent",
                        "customers:consent")
                .POST(HttpRequest.BodyPublishers.ofString("{\"category\":\"MARKETING\",\"channel\":\"SMS\"}"))
                .build());

        // Recording "denied" for a question nobody asked would fabricate a customer's answer, and
        // the compliance value of this table is that every row in it is a real one. 422 rather than
        // 400, matching REASON_REQUIRED and TIER_UNCHANGED: the request parsed, its content was
        // unacceptable.
        assertThat(incomplete.statusCode()).isEqualTo(422);
        assertThat(incomplete.body()).contains("CONSENT_INCOMPLETE");
    }

    @Test
    @DisplayName("consent for a customer that does not exist is 404")
    void consent_needs_a_customer() {
        assertThat(recordConsent(UUID.randomUUID().toString(), "MARKETING", "SMS", true).statusCode())
                .isEqualTo(404);
    }
}
