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
 * Asks Core who holds an account and what they agreed to.
 *
 * <p>The only outbound call this service makes on the send path, and deliberately the only one it
 * can make: there is no ledger client here and no gateway SDK, and the POM is where a reviewer
 * checks that.
 *
 * <p>Failures are not swallowed. A directory that answers "no contact" when Core is merely
 * unreachable would turn an outage into a stream of {@code NO_ADDRESS} suppressions — messages
 * permanently not sent, each with a recorded reason that is a lie. So an unreachable Core throws,
 * the intake leaves the event unconsumed, and redelivery retries it.
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
            @Value("${fincore.notification.core.base-url:http://localhost:8081}") String baseUrl,
            @Value("${fincore.notification.core.timeout-ms:2000}") long timeoutMs,
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
            log.warn("core answered {} for account lookup", response.statusCode());
            throw new DirectoryUnavailable(new IllegalStateException("core returned " + response.statusCode()));
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

    /** Core could not be reached or answered unusably. Distinct from "no such customer". */
    public static class DirectoryUnavailable extends RuntimeException {
        public DirectoryUnavailable(Throwable cause) {
            super("customer directory unavailable", cause);
        }
    }
}
