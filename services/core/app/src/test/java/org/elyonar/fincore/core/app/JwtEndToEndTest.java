package org.elyonar.fincore.core.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The jwt lane, end to end (ui-runway.md §2): Core in {@code mode=jwt} against a local JWKS —
 * real RS256 tokens, verified locally per request, no identity provider in the loop — driving a
 * full money path, with the outbound side asserted too: the ledger stub must receive
 * Core's <em>service credential</em> and the originating user's <em>forwarded token</em> (outbound
 * propagation), which is exactly what {@code LedgerAuth} on the other side requires.
 *
 * <p>"Works with real tokens" is a claim; this suite is what makes it one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FakeServices.class)
class JwtEndToEndTest {

    // Static init, not @BeforeAll: @DynamicPropertySource reads these while the context builds.
    private static final HttpServer jwks;
    private static final HttpServer ledger;
    private static final RSAKey key;
    private static final String issuer;
    private static final String serviceToken;
    private static final AtomicReference<String> ledgerAuthHeader = new AtomicReference<>();
    private static final AtomicReference<String> ledgerForwardedHeader = new AtomicReference<>();

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            key =
                    new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                            .privateKey((RSAPrivateKey) pair.getPrivate())
                            .keyID("test-key")
                            .build();

            jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] keys = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            jwks.createContext(
                    "/jwks",
                    exchange -> {
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, keys.length);
                        exchange.getResponseBody().write(keys);
                        exchange.close();
                    });
            jwks.start();
            issuer = "http://127.0.0.1:" + jwks.getAddress().getPort();

            serviceToken =
                    sign(
                            new JWTClaimsSet.Builder()
                                    .issuer(issuer)
                                    .subject("service-account-core")
                                    .claim("azp", "core")
                                    .expirationTime(new Date(System.currentTimeMillis() + 3_600_000))
                                    .build());

            ledger = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ledger.createContext(
                    "/",
                    exchange -> {
                        ledgerAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
                        ledgerForwardedHeader.set(
                                exchange.getRequestHeaders().getFirst("X-Forwarded-Authorization"));
                        exchange.getRequestBody().readAllBytes();
                        byte[] body =
                                ("{\"transactionId\":\"" + UUID.randomUUID() + "\"}").getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders("POST".equals(exchange.getRequestMethod()) ? 201 : 200, body.length);
                        exchange.getResponseBody().write(body);
                        exchange.close();
                    });
            ledger.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @AfterAll
    static void stop() {
        jwks.stop(0);
        ledger.stop(0);
    }

    private static String sign(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @DynamicPropertySource
    static void jwtMode(DynamicPropertyRegistry registry) {
        registry.add("fincore.auth.mode", () -> "jwt");
        registry.add("fincore.auth.issuer-uri", () -> issuer);
        registry.add("fincore.auth.jwks-uri", () -> issuer + "/jwks");
        registry.add("fincore.core.ledger.base-url", () -> "http://127.0.0.1:" + ledger.getAddress().getPort());
        registry.add("fincore.core.ledger.service-token", () -> serviceToken);
        registry.add("fincore.core.worker.interval-ms", () -> "3600000");
        registry.add("fincore.core.outbox.relay.interval-ms", () -> "3600000");
        registry.add("fincore.test.context", () -> "jwt-e2e");
    }

    @Autowired private TenantRegistry tenantRegistry;
    @Autowired private FakeServices.FakeCustomers customers;
    @Autowired private FakeServices.FakePricing pricing;
    @LocalServerPort private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper mapper = JsonMapper.builder().build();

    private UUID tenantId;
    private UUID customerId;
    private UUID customerAccount;

    private String userToken(String username, List<String> permissions) throws Exception {
        return sign(
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .subject(username)
                        .claim("preferred_username", username)
                        .claim("tenant_id", tenantId.toString())
                        .claim("permissions", permissions)
                        .expirationTime(new Date(System.currentTimeMillis() + 300_000))
                        .build());
    }

    @BeforeEach
    void seed() {
        tenantId = UUID.randomUUID();
        tenantRegistry.register(tenantId, "jwt tenant", "test");
        customerId = UUID.randomUUID();
        customerAccount = UUID.randomUUID();
        // Customer and Product are deployables now (ADR 0020): the premise these blocks
        // built in SQL is stated directly, and the assertions below are unchanged.
        customers.clear();
        customers.eligible(customerId, "TIER_2").holds(customerId, customerAccount, "P", "NGN");
        pricing.permits(0, null, 5000000);
    }

    private HttpResponse<String> call(String method, String path, String token, String body) {
        var builder =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        var request =
                "GET".equals(method)
                        ? builder.GET().build()
                        : builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void without_a_token_the_surface_is_closed_and_dev_headers_convince_nobody() throws Exception {
        assertThat(call("GET", "/v1/customers?q=Ada", null, null).statusCode()).isEqualTo(401);
        // Dev headers are inert in jwt mode — asserting a tenant is not having one.
        var withHeaders =
                http.send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/customers?q=Ada"))
                                .header("X-Dev-Tenant-Id", tenantId.toString())
                                .header("X-Dev-Principal", "user:x")
                                .header("X-Dev-Permissions", "customers:read")
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(withHeaders.statusCode()).isEqualTo(401);
        // Health stays open: an orchestrator needs no credential to ask if we are alive.
        assertThat(call("GET", "/actuator/health", null, null).statusCode()).isEqualTo(200);
    }

    @Test
    void a_verified_token_without_the_permission_is_a_403() throws Exception {
        // Probes an endpoint Core still serves. `/v1/customers?q=` moved to the Customer service
        // with ADR 0020; the assertion is about the token, not the route, so the route followed.
        String token = userToken("ada", List.of("transfers:read"));
        assertThat(call("GET", "/v1/org-units", token, null).statusCode()).isEqualTo(403);
    }

    /**
     * A money path on real tokens, with the outbound side asserted too.
     *
     * <p>Was the lending disbursement until lending left this build; the property under test never
     * had anything to do with loans. It is that a request authenticated by a real token reaches the
     * Ledger carrying <em>two</em> identities — Core's own service credential, and the originating
     * user's token forwarded so attribution is a verified fact rather than a claim in a payload
     * (ADR 0014). A deposit exercises the same path.
     */
    @Test
    void a_money_path_runs_on_real_tokens_and_propagates_identity_to_the_ledger() throws Exception {
        String teller =
                userToken("teller", List.of("transfers:create", "channel:teller"));

        var posted =
                call(
                        "POST",
                        "/v1/transfers",
                        teller,
                        ("{\"idempotencyKey\":\"jwt-%s\",\"fingerprint\":\"fp\",\"customerId\":\"%s\","
                                        + "\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\","
                                        + "\"amountMinor\":100000,\"currency\":\"NGN\","
                                        + "\"channel\":\"TELLER\",\"description\":\"d\"}")
                                .formatted(UUID.randomUUID(), customerId, customerAccount, UUID.randomUUID()));
        assertThat(posted.statusCode()).isEqualTo(201);
        assertThat(mapper.readTree(posted.body()).get("state").asString()).isEqualTo("COMPLETED");

        // The outbound side of the runway: the ledger received Core's service credential AND the
        // originating teller's own token — attribution as a verified fact (ADR 0014).
        assertThat(ledgerAuthHeader.get()).isEqualTo("Bearer " + serviceToken);
        assertThat(ledgerForwardedHeader.get()).isEqualTo("Bearer " + teller);
    }
}
