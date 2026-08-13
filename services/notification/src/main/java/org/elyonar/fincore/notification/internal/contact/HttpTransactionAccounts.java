package org.elyonar.fincore.notification.internal.contact;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads a transaction's accounts back from Core's non-mutating status endpoint. */
@Component
public class HttpTransactionAccounts implements TransactionAccounts {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http;
    private final String baseUrl;
    private final Duration timeout;

    private final ServiceTokens tokens;

    public HttpTransactionAccounts(
            @Value("${fincore.notification.core.base-url:http://localhost:8081}") String baseUrl,
            @Value("${fincore.notification.core.timeout-ms:2000}") long timeoutMs,
            ServiceTokens tokens) {
        this.baseUrl = baseUrl;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
        this.tokens = tokens;
    }

    @Override
    public Optional<Accounts> forTransaction(UUID tenantId, UUID transactionId) {
        // A real bearer, minted for this event's tenant (ADR 0019). The development identity
        // headers this used to send became inert the day `jwt` became the default mode, and Core
        // answered 401 to every attempt.
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/transactions/" + transactionId))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + tokens.forTenant(tenantId))
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            // Unreachable is not "no such transaction". Throwing leaves the event unconsumed so
            // redelivery retries it; answering empty would suppress a message that was owed, with
            // a recorded reason that is false.
            throw new HttpContactDirectory.DirectoryUnavailable(e);
        }

        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new HttpContactDirectory.DirectoryUnavailable(
                    new IllegalStateException("core returned " + response.statusCode()));
        }

        JsonNode body = JSON.readTree(response.body());
        return Optional.of(new Accounts(
                uuidOrNull(body, "fromAccountId"),
                uuidOrNull(body, "toAccountId"),
                new Facts(
                        textOrNull(body, "reference"),
                        textOrNull(body, "type"),
                        longOrZero(body, "amountMinor"),
                        longOrZero(body, "feeMinor"),
                        textOrNull(body, "currency"),
                        textOrNull(body, "channel"),
                        timeOrNull(body, "createdAt"))));
    }

    private static String textOrNull(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    /**
     * Money as a number, however Core spelled it.
     *
     * <p>Core serializes amounts as decimal *strings* — balances elsewhere on this platform exceed
     * exact JSON number range, and one rule everywhere is what stops a consumer silently losing
     * precision. Reading it as a long here is safe because a single transaction's minor units are
     * nowhere near that ceiling, and it is what the segment counter and the money filter need.
     */
    private static long longOrZero(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            return 0L;
        }
        return value.isNumber() ? value.asLong() : Long.parseLong(value.asString());
    }

    private static java.time.OffsetDateTime timeOrNull(JsonNode body, String field) {
        String raw = textOrNull(body, field);
        if (raw == null) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(raw);
        } catch (java.time.format.DateTimeParseException e) {
            // A date this service cannot read is a date it will not put in front of a customer.
            return null;
        }
    }

    private static UUID uuidOrNull(JsonNode body, String field) {
        JsonNode value = body.get(field);
        // Null is a real answer here, not a parse failure: a reversal names no accounts.
        return value == null || value.isNull() ? null : UUID.fromString(value.asString());
    }
}
