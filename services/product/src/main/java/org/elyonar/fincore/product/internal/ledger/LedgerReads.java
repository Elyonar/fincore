package org.elyonar.fincore.product.internal.ledger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.elyonar.fincore.product.api.LedgerAccounts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Product asking the ledger about an account, on its own behalf.
 *
 * <p>This is the class that made the extraction worth doing on its own terms. Inside Core, Product
 * declared {@link LedgerAccounts} and <em>Orchestration implemented it</em> — a port pointing
 * backwards, invented purely because a Core module was forbidden an HTTP client (hard rule 3). A
 * deployable is allowed one, so the answer comes from the ledger directly and the inversion is
 * gone.
 *
 * <p><strong>Read-only, and structurally so.</strong> The only method here issues GET. Nothing in
 * this service can create a ledger account or post to one even by accident, and the POM is where a
 * reviewer confirms there is no path to it.
 *
 * <p><strong>Authoring only.</strong> This is called when a fee rule names the account its income
 * lands in — the check {@code V4__fee_account_configuration.sql} was written about, because
 * accepting that unverified is how a customer's fee ends up in somebody's savings. It is never on
 * the decision path: evaluating a published version touches nothing but this service's own
 * database.
 *
 * <p><strong>Three answers, never two.</strong> A 5xx or an unreachable ledger is
 * {@code Unreadable} and never {@code Absent}. Refusing a correctly authored fee rule because the
 * ledger happened to be restarting would be the read-side version of compensating an unknown
 * outcome.
 */
@Component
public class LedgerReads implements LedgerAccounts {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Renew this long before expiry, so a token in flight is never one that has just lapsed. */
    private static final Duration EARLY = Duration.ofSeconds(30);

    private final HttpClient http;
    private final String ledgerBaseUrl;
    private final String identityBaseUrl;
    private final String clientId;
    private final String clientSecret;
    private final Duration timeout;
    private final ConcurrentHashMap<UUID, Minted> tokens = new ConcurrentHashMap<>();

    public LedgerReads(
            @Value("${fincore.product.ledger.base-url:http://localhost:8080}") String ledgerBaseUrl,
            @Value("${fincore.product.identity.base-url:http://localhost:8083}") String identityBaseUrl,
            @Value("${fincore.product.identity.client-id:product}") String clientId,
            @Value("${fincore.product.identity.client-secret:}") String clientSecret,
            @Value("${fincore.product.ledger.timeout-ms:2000}") long timeoutMs) {
        this.ledgerBaseUrl = ledgerBaseUrl;
        this.identityBaseUrl = identityBaseUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    @Override
    public Account describe(UUID tenantId, UUID accountId) {
        if (accountId == null) {
            return new Account.Absent();
        }

        String bearer;
        try {
            bearer = forTenant(tenantId);
        } catch (RuntimeException e) {
            // This service's own credential being refused is a deployment fault, not a fact about
            // the account. It must not read as "no such account".
            return new Account.Unreadable("could not mint a service credential");
        }

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(ledgerBaseUrl + "/v1/accounts/" + accountId))
                .timeout(timeout)
                .header("Authorization", "Bearer " + bearer)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            return new Account.Unreadable("ledger unreachable");
        }

        int status = response.statusCode();
        if (status == 404) {
            // The ledger answers another tenant's account with 404 too, deliberately. Product must
            // not try to tell the two apart, and does not need to: both mean "you may not name it".
            return new Account.Absent();
        }
        if (status != 200) {
            // A 400 or a 401 here is a defect in this service — a malformed path, or a credential
            // the ledger will not take. Neither is a fact about the account, so neither is Absent.
            return new Account.Unreadable("ledger returned " + status);
        }

        try {
            JsonNode parsed = JSON.readTree(response.body());
            return new Account.Known(
                    parsed.path("type").asString(),
                    parsed.path("currency").asString(),
                    parsed.path("status").asString());
        } catch (RuntimeException e) {
            // A 200 we cannot read is not an account we can vouch for.
            return new Account.Unreadable("unreadable ledger response");
        }
    }

    /**
     * A bearer good for this tenant, minted if the cached one is missing or nearly spent (ADR 0019).
     *
     * <p>Minted <em>for the tenant being served</em> rather than for this service, which is why it
     * caches per tenant. A service asserting its own scope on the call would be the caller-asserted
     * scope the platform refuses everywhere.
     */
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
            throw new IllegalStateException(
                    "identity refused this service's credential: " + response.statusCode());
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
}
