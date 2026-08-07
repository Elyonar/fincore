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

    public HttpTransactionAccounts(
            @Value("${fincore.notification.core.base-url:http://localhost:8081}") String baseUrl,
            @Value("${fincore.notification.core.timeout-ms:2000}") long timeoutMs) {
        this.baseUrl = baseUrl;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    @Override
    public Optional<Accounts> forTransaction(UUID tenantId, UUID transactionId) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/transactions/" + transactionId))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("X-Dev-Tenant-Id", tenantId.toString())
                .header("X-Dev-Principal", "service:notification")
                .header("X-Dev-Permissions", "transfers:read")
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
        return Optional.of(new Accounts(uuidOrNull(body, "fromAccountId"), uuidOrNull(body, "toAccountId")));
    }

    private static UUID uuidOrNull(JsonNode body, String field) {
        JsonNode value = body.get(field);
        // Null is a real answer here, not a parse failure: a reversal names no accounts.
        return value == null || value.isNull() ? null : UUID.fromString(value.asString());
    }
}
