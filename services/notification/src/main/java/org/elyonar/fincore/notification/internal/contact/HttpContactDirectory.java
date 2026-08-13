package org.elyonar.fincore.notification.internal.contact;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asks the Customer service who holds an account and what they agreed to.
 *
 * <p><strong>Customer, not Core.</strong> {@code GET /v1/customers/by-account/{id}} left Core with
 * ADR 0020 and is served by the customer deployable. This client asked Core for it after the
 * extraction, and the failure was invisible: Core answers 404 for a path it no longer routes, 404
 * is this class's documented "no such customer", and the intake therefore recorded a legitimate
 * {@code UNKNOWN_ACCOUNT} suppression for every side of every transfer. No error, no warning, no
 * retry — the platform reported itself working and produced nothing. {@code BoundaryTest} now
 * fails if this client is ever pointed back at Core's base URL.
 *
 * <p>Two outbound calls exist on the send path and they address different services: this one, and
 * {@link HttpTransactionAccounts} asking Core which accounts a transaction moved between. Both are
 * reads. There is no ledger client here and no gateway SDK, and the POM is where a reviewer checks
 * that.
 *
 * <p>Failures are not swallowed. A directory that answers "no contact" when the customer service is
 * merely unreachable would turn an outage into a stream of {@code NO_ADDRESS} suppressions —
 * messages permanently not sent, each with a recorded reason that is a lie. So an unreachable
 * dependency throws, the intake leaves the event unconsumed, and redelivery retries it.
 */
@Component
public class HttpContactDirectory implements ContactDirectory {

    private static final Logger log = LoggerFactory.getLogger(HttpContactDirectory.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http;
    private final String baseUrl;
    private final Duration timeout;

    private final ServiceTokens tokens;

    public HttpContactDirectory(
            @Value("${fincore.notification.customer.base-url:http://localhost:8085}") String baseUrl,
            @Value("${fincore.notification.customer.timeout-ms:2000}") long timeoutMs,
            ServiceTokens tokens) {
        this.baseUrl = baseUrl;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
        this.tokens = tokens;
    }

    @Override
    public Optional<Contact> forAccount(UUID tenantId, UUID ledgerAccountId) {
        // The principal is this service, scoped to the event's tenant and carrying only the
        // permissions its client was declared to hold (ADR 0019). Which *system* placed the call
        // remains ADR 0009's separate question, answered by the TLS peer and still unanswered
        // until mTLS is deployed.
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/v1/customers/by-account/" + ledgerAccountId))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + tokens.forTenant(tenantId))
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            // Unreachable is not "no such customer". Throwing leaves the event unconsumed so
            // redelivery retries it; answering empty would suppress a message that was owed.
            throw new DirectoryUnavailable(e);
        }

        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            log.warn("customer service answered {} for account lookup", response.statusCode());
            throw new DirectoryUnavailable(
                    new IllegalStateException("customer service returned " + response.statusCode()));
        }

        JsonNode body = JSON.readTree(response.body());
        Map<String, String> addresses = new LinkedHashMap<>();
        JsonNode node = body.get("addresses");
        if (node != null) {
            node.properties().forEach(entry -> addresses.put(entry.getKey(), entry.getValue().asString()));
        }

        List<Consent> consent = new ArrayList<>();
        JsonNode consentNode = body.get("consent");
        if (consentNode != null) {
            consentNode.forEach(c -> consent.add(new Consent(
                    c.get("category").asString(), c.get("channel").asString(), c.get("granted").asBoolean())));
        }

        JsonNode locale = body.get("locale");
        JsonNode accountNumber = body.get("accountNumber");
        return Optional.of(new Contact(
                UUID.fromString(body.get("customerId").asString()),
                body.get("status").asString(),
                locale == null || locale.isNull() ? null : locale.asString(),
                accountNumber == null || accountNumber.isNull() ? null : accountNumber.asString(),
                addresses,
                consent));
    }

    /** The customer service could not be reached or answered unusably. Distinct from "no such customer". */
    public static class DirectoryUnavailable extends RuntimeException {
        public DirectoryUnavailable(Throwable cause) {
            super("customer directory unavailable", cause);
        }
    }
}
