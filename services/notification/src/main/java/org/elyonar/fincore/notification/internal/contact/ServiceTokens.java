package org.elyonar.fincore.notification.internal.contact;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * This service's own credential for reading one tenant's data from Core (ADR 0019).
 *
 * <p>Notification asks Core two questions per event — which accounts a transfer moved between, and
 * who holds them — and both are ordinary tenant-scoped reads behind ordinary permissions. It used
 * to ask with the development identity headers, which stopped meaning anything the moment {@code
 * jwt} became the default mode: Core answered 401, the intake threw, the Kafka offset was never
 * committed, and the same event was redelivered about once a second forever while no notification
 * was ever produced and nothing said so.
 *
 * <p>The token is minted <em>for the event's tenant</em>, which is why this caches per tenant
 * rather than holding one. A service asserting its own tenant on the call instead would be the
 * caller-asserted scope the platform refuses everywhere, and its failure mode — reading another
 * institution's customers — is exactly the one nothing downstream could catch.
 *
 * <p>Refreshed early, so a token never expires mid-call. A mint that fails throws, and the caller
 * turns that into the same unconsumed-event outcome an unreachable Core produces: retrying an
 * event is recoverable, sending nothing and recording a reason that is false is not.
 */
@Component
public class ServiceTokens {

    /** Renew this long before expiry, so a token in flight is never one that has just lapsed. */
    private static final Duration EARLY = Duration.ofSeconds(30);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http;
    private final String identityBaseUrl;
    private final String clientId;
    private final String clientSecret;
    private final Duration timeout;
    private final ConcurrentHashMap<UUID, Minted> cache = new ConcurrentHashMap<>();

    public ServiceTokens(
            @Value("${fincore.notification.identity.base-url:http://localhost:8083}") String identityBaseUrl,
            @Value("${fincore.notification.identity.client-id:notification}") String clientId,
            @Value("${fincore.notification.identity.client-secret:}") String clientSecret,
            @Value("${fincore.notification.core.timeout-ms:2000}") long timeoutMs) {
        this.identityBaseUrl = identityBaseUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    /** A bearer good for this tenant, minted if the cached one is missing or nearly spent. */
    public String forTenant(UUID tenantId) {
        Minted cached = cache.get(tenantId);
        if (cached != null && Instant.now().isBefore(cached.expiry())) {
            return cached.token();
        }
        Minted minted = mint(tenantId);
        cache.put(tenantId, minted);
        return minted.token();
    }

    private Minted mint(UUID tenantId) {
        String body = JSON.writeValueAsString(
                Map.of("clientId", clientId, "clientSecret", clientSecret, "tenantId", tenantId.toString()));
        HttpRequest request = HttpRequest.newBuilder(URI.create(identityBaseUrl + "/v1/auth/token"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new HttpContactDirectory.DirectoryUnavailable(e);
        }
        if (response.statusCode() != 200) {
            // This service's own credential being refused is a deployment fault, not a fact about
            // the event. Same treatment either way — the event stays unconsumed — but the message
            // has to name the right thing for whoever reads it.
            throw new HttpContactDirectory.DirectoryUnavailable(
                    new IllegalStateException(
                            "identity refused this service's credential: " + response.statusCode()));
        }
        JsonNode minted = JSON.readTree(response.body());
        JsonNode token = minted.get("accessToken");
        if (token == null || token.isNull()) {
            throw new HttpContactDirectory.DirectoryUnavailable(
                    new IllegalStateException("no token in mint response"));
        }
        JsonNode ttl = minted.get("expiresIn");
        long seconds = ttl == null || ttl.isNull() ? 300 : ttl.asLong();
        return new Minted(token.asString(), Instant.now().plusSeconds(seconds).minus(EARLY));
    }

    private record Minted(String token, Instant expiry) {}
}
