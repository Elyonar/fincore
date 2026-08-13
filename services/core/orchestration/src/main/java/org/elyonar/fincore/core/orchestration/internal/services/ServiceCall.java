package org.elyonar.fincore.core.orchestration.internal.services;

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
 * Core calling another of the platform's services, on behalf of one tenant.
 *
 * <p>Shared by the Product and Customer clients so there is one place that mints a credential, one
 * timeout policy and one definition of "could not ask" (ADR 0020). Two clients each inventing that
 * is two clients that will eventually disagree about what a 503 means on the money path.
 *
 * <p><strong>It fails closed, and that is the whole design.</strong> Every failure — a timeout, an
 * unreachable service, a 5xx, a credential the callee will not take — throws {@link Unavailable}.
 * Nothing here returns a default, a stale answer or an empty result. An unreachable pricing service
 * is not "no limit", and an unreachable customer service is not "no such customer": both are "we do
 * not know", and a transaction whose eligibility is unknown must be refused rather than allowed.
 * This is the same rule the ledger client follows for the same reason.
 *
 * <p>The token is minted <em>for the tenant being served</em>, which is why it caches per tenant
 * rather than holding one. Core asserting its own scope on the call would be the caller-asserted
 * scope the platform refuses everywhere, and its failure mode — reading another institution's
 * customers — is the one nothing downstream could catch.
 */
@Component
public class ServiceCall {

    /** Renew this long before expiry, so a token in flight is never one that has just lapsed. */
    private static final Duration EARLY = Duration.ofSeconds(30);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http;
    private final String identityBaseUrl;
    private final String clientId;
    private final String clientSecret;
    private final Duration timeout;
    private final ConcurrentHashMap<UUID, Minted> tokens = new ConcurrentHashMap<>();

    public ServiceCall(
            @Value("${fincore.core.identity.base-url:http://localhost:8083}") String identityBaseUrl,
            @Value("${fincore.core.identity.client-id:core}") String clientId,
            @Value("${fincore.core.identity.client-secret:}") String clientSecret,
            @Value("${fincore.core.services.timeout-ms:2000}") long timeoutMs) {
        this.identityBaseUrl = identityBaseUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    /** A GET returning parsed JSON, or {@link Unavailable}. A 404 returns null. */
    public JsonNode get(UUID tenantId, String baseUrl, String pathAndQuery) {
        return send(tenantId, HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery)).GET());
    }

    /** A POST of a JSON body returning parsed JSON, or {@link Unavailable}. A 404 returns null. */
    public JsonNode post(UUID tenantId, String baseUrl, String path, Map<String, ?> body) {
        return send(
                tenantId,
                HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
    }

    /** A PUT of a JSON body. The rule sets are replacements, so the verb says so. */
    public JsonNode put(UUID tenantId, String baseUrl, String path, Map<String, ?> body) {
        return send(
                tenantId,
                HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
    }

    /** A PATCH of a JSON body, for changing one field of a version rather than replacing it. */
    public JsonNode patch(UUID tenantId, String baseUrl, String path, Map<String, ?> body) {
        return send(
                tenantId,
                HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
    }

    private JsonNode send(UUID tenantId, HttpRequest.Builder builder) {
        String bearer;
        try {
            bearer = forTenant(tenantId);
        } catch (RuntimeException e) {
            throw new Unavailable("could not mint a service credential", e);
        }

        HttpResponse<String> response;
        try {
            response = http.send(
                    builder.timeout(timeout)
                            .header("Authorization", "Bearer " + bearer)
                            .header("Accept", "application/json")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new Unavailable("service unreachable", e);
        }

        int status = response.statusCode();
        if (status == 404) {
            // Absent, and deliberately indistinguishable from another tenant's. The caller decides
            // what a null means for its own question.
            return null;
        }
        if (status < 200 || status >= 300) {
            // Includes 401 and 403, which on this path are a Core deployment fault rather than a
            // fact about the subject — and must not read as "not eligible".
            throw new Unavailable("service returned " + status, null);
        }

        try {
            return JSON.readTree(response.body());
        } catch (RuntimeException e) {
            throw new Unavailable("unreadable response", e);
        }
    }

    private String forTenant(UUID tenantId) {
        Minted cached = tokens.get(tenantId);
        if (cached != null && Instant.now().isBefore(cached.expiry())) {
            return cached.token();
        }
        Minted minted = mint(tenantId);
        tokens.put(tenantId, minted);
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
            throw new IllegalStateException("identity unreachable", e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("identity refused Core's credential: " + response.statusCode());
        }

        JsonNode minted = JSON.readTree(response.body());
        JsonNode token = minted.get("accessToken");
        if (token == null || token.isNull()) {
            throw new IllegalStateException("no token in mint response");
        }
        JsonNode ttl = minted.get("expiresIn");
        long seconds = ttl == null || ttl.isNull() ? 300 : ttl.asLong();
        return new Minted(token.asString(), Instant.now().plusSeconds(seconds).minus(EARLY));
    }

    private record Minted(String token, Instant expiry) {}

    /**
     * We could not get an answer.
     *
     * <p>Distinct from any answer, including a negative one. Callers on the money path turn this
     * into a refusal that says the platform could not decide — never into a refusal that claims
     * something about the customer or the product, because that would be a false statement written
     * into a transaction record.
     */
    public static class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
