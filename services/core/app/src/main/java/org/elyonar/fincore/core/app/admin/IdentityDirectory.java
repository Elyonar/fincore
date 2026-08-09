package org.elyonar.fincore.core.app.admin;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Core's client for the identity service's staff directory (ADR 0018).
 *
 * <p>The second outbound dependency in Core, and it follows the first one's rules exactly. Core
 * authenticates as a <em>service</em> — a client-credentials token it mints and re-mints for
 * itself — and forwards the administrator's own token alongside it, so the directory records who
 * actually asked rather than a string Core could have invented. That is the ledger's pattern
 * ({@code X-Forwarded-Authorization}), reused because a second way of saying "this service, for
 * that human" would be a second thing to get wrong.
 *
 * <p>The service token is minted at need and cached until shortly before it expires. Core does not
 * carry a long-lived secret token in configuration: a ten-minute credential re-minted on demand is
 * strictly better than one that lives as long as the deployment, and it closes the static-token
 * residual ADR 0018 left behind.
 */
@Component
public class IdentityDirectory {

    /** Re-mint this long before expiry, so a call never carries a token that dies in flight. */
    private static final Duration EARLY = Duration.ofSeconds(30);

    private final RestClient http;
    private final String clientId;
    private final String clientSecret;

    private final tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();

    private volatile String token;
    private volatile Instant expiry = Instant.EPOCH;

    public IdentityDirectory(
            @Value("${fincore.core.identity.base-url:http://localhost:8083}") String baseUrl,
            @Value("${fincore.core.identity.client-id:core}") String clientId,
            @Value("${fincore.core.identity.client-secret:}") String clientSecret,
            @Value("${fincore.core.identity.timeout-ms:3000}") int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /** True when this deployment gave Core a credential to speak to the directory with. */
    public boolean configured() {
        return clientSecret != null && !clientSecret.isBlank();
    }

    // --- the surface ---------------------------------------------------------------------------

    public JsonNode permissions() {
        return exchange("GET", "/v1/directory/permissions", null);
    }

    public JsonNode roles() {
        return exchange("GET", "/v1/directory/roles", null);
    }

    public JsonNode users(String role, String unit, String cursor) {
        StringBuilder template = new StringBuilder("/v1/directory/users?");
        List<Object> vars = new java.util.ArrayList<>();
        if (role != null && !role.isBlank()) {
            template.append("role={role}&");
            vars.add(role);
        }
        if (unit != null && !unit.isBlank()) {
            template.append("unit={unit}&");
            vars.add(unit);
        }
        if (cursor != null && !cursor.isBlank()) {
            template.append("cursor={cursor}");
            vars.add(cursor);
        }
        return exchange("GET", template.toString(), null, vars.toArray());
    }

    public JsonNode user(UUID id) {
        return exchange("GET", "/v1/directory/users/{id}", null, id);
    }

    public JsonNode createRole(Map<String, Object> body) {
        return exchange("POST", "/v1/directory/roles", body);
    }

    public JsonNode setRolePermissions(String role, List<String> permissions) {
        return exchange(
                "PUT", "/v1/directory/roles/{role}/permissions", Map.of("permissions", permissions), role);
    }

    public JsonNode deleteRole(String role) {
        return exchange("DELETE", "/v1/directory/roles/{role}", Map.of(), role);
    }

    public JsonNode createUser(Map<String, Object> body) {
        return exchange("POST", "/v1/directory/users", body);
    }

    public JsonNode setUnits(UUID id, List<String> units) {
        return exchange("PUT", "/v1/directory/users/{id}/units", Map.of("units", units), id);
    }

    public JsonNode setUserRoles(UUID id, List<String> roles) {
        return exchange("PUT", "/v1/directory/users/{id}/roles", Map.of("roles", roles), id);
    }

    public JsonNode resetPassword(UUID id) {
        return exchange("POST", "/v1/directory/users/{id}/reset-password", Map.of(), id);
    }

    public JsonNode unlock(UUID id) {
        return exchange("POST", "/v1/directory/users/{id}/unlock", Map.of(), id);
    }

    // --- plumbing ------------------------------------------------------------------------------

    private JsonNode exchange(String method, String path, Object body, Object... vars) {
        RawResponse response = attempt(method, path, body, vars);

        // A 401 is almost never about the administrator here — their token was verified by
        // libs/auth before this class was reached. It is Core's own service credential having
        // gone stale: identity restarted and, in dev, came back with a new signing key, so the
        // token cached a moment ago no longer verifies. Discard it and try once more. Without
        // this, every restart of identity breaks administration for the remainder of a token's
        // lifetime, and the error blames the wrong party.
        if (response.status() == 401) {
            synchronized (this) {
                token = null;
                expiry = Instant.EPOCH;
            }
            response = attempt(method, path, body, vars);
        }

        JsonNode parsed = parse(response.body());
        if (response.status() >= 400) {
            throw new DirectoryRefused(response.status(), parsed);
        }
        return parsed;
    }

    private RawResponse attempt(String method, String path, Object body, Object... vars) {
        RawResponse response;
        try {
            var request = http.method(org.springframework.http.HttpMethod.valueOf(method))
                    .uri(path, vars)
                    .headers(this::identity);
            var spec = body == null
                    ? request
                    : request.contentType(MediaType.APPLICATION_JSON).body(body);
            // Every status is handled here rather than thrown, so a refusal the directory
            // deliberately shaped travels to the client intact instead of becoming a 500.
            response = spec.exchange(
                    (req, res) -> new RawResponse(res.getStatusCode().value(), readBody(res)), false);
        } catch (RestClientException | java.io.UncheckedIOException e) {
            // The directory being unreachable is a documented refusal of its own (admin-surface
            // §5: DIRECTORY_UNREACHABLE, 503) and never a 500: an administrator retrying in a
            // minute is the right advice, and a stack trace is not.
            throw new DirectoryUnreachable(e);
        }
        return response;
    }

    private record RawResponse(int status, String body) {}

    private static String readBody(org.springframework.http.client.ClientHttpResponse response) {
        try (var in = response.getBody()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /** Core's own credential, plus the administrator's token, on every call. */
    private void identity(HttpHeaders headers) {
        headers.set("Authorization", "Bearer " + serviceToken());
        org.elyonar.fincore.auth.Authorization.bearer()
                .ifPresent(bearer -> headers.set("X-Forwarded-Authorization", "Bearer " + bearer));
    }

    private String serviceToken() {
        String current = token;
        if (current != null && Instant.now().isBefore(expiry)) {
            return current;
        }
        synchronized (this) {
            if (token != null && Instant.now().isBefore(expiry)) {
                return token;
            }
            RawResponse response;
            try {
                response = http.post()
                        .uri("/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("clientId", clientId, "clientSecret", clientSecret))
                        .exchange(
                                (req, res) -> new RawResponse(res.getStatusCode().value(), readBody(res)),
                                false);
            } catch (RestClientException | java.io.UncheckedIOException e) {
                throw new DirectoryUnreachable(e);
            }
            JsonNode minted = parse(response.body());
            if (response.status() >= 400) {
                // Core's own credential being refused is a deployment fault, not the
                // administrator's: it must not read as "your request was bad".
                throw new DirectoryUnreachable(
                        new IllegalStateException("identity refused Core's service credential"));
            }
            if (minted == null || minted.get("accessToken") == null) {
                throw new DirectoryUnreachable(new IllegalStateException("no token in mint response"));
            }
            token = minted.get("accessToken").asString();
            long ttl = minted.get("expiresIn") == null ? 300 : minted.get("expiresIn").asLong();
            expiry = Instant.now().plusSeconds(ttl).minus(EARLY);
            return token;
        }
    }

    /** The directory answered, and the answer was no. Its body is passed through, translated. */
    public static class DirectoryRefused extends RuntimeException {
        public final int status;
        public final transient JsonNode body;

        DirectoryRefused(int status, JsonNode body) {
            super("directory refused: " + status);
            this.status = status;
            this.body = body;
        }
    }

    /** The directory did not answer. Distinct from a refusal, because the advice differs. */
    public static class DirectoryUnreachable extends RuntimeException {
        DirectoryUnreachable(Throwable cause) {
            super("directory unreachable", cause);
        }
    }
}
