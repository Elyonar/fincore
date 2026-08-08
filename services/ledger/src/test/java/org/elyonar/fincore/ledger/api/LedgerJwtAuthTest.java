package org.elyonar.fincore.ledger.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.UUID;
import org.elyonar.fincore.ledger.support.LedgerPostgresTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The jwt lane (ADR 0014): real tokens, minted against a local JWKS, verified by the ledger the
 * way a deployment would — so "the tenant header died" is a claim with a test, not a config
 * comment. The rules under test:
 *
 * <ul>
 *   <li>no credential → 401; a user token presented directly → 401 (clients never address the
 *       ledger); an unlisted service → 401;
 *   <li>a trusted service credential may assert the tenant for its system jobs;
 *   <li>a forwarded user token decides the tenant <em>and</em> the posting's attribution —
 *       verified fact over copied string.
 * </ul>
 */
@AutoConfigureMockMvc
@DisplayName("HTTP — jwt identity: who may address the ledger")
class LedgerJwtAuthTest extends LedgerPostgresTest {

    private static HttpServer jwks;
    private static RSAKey key;
    private static String issuer;

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenant;

    @BeforeAll
    static void startIssuer() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        key =
                new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                        .privateKey((RSAPrivateKey) pair.getPrivate())
                        .keyID("test-key")
                        .build();
        jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body =
                new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        jwks.createContext(
                "/jwks",
                exchange -> {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        jwks.start();
        issuer = "http://127.0.0.1:" + jwks.getAddress().getPort();
    }

    @AfterAll
    static void stopIssuer() {
        jwks.stop(0);
    }

    @DynamicPropertySource
    static void jwtMode(DynamicPropertyRegistry registry) {
        registry.add("fincore.ledger.auth.mode", () -> "jwt");
        registry.add("fincore.ledger.auth.issuer-uri", () -> issuer);
        registry.add("fincore.ledger.auth.jwks-uri", () -> issuer + "/jwks");
    }

    @BeforeEach
    void seedTenant() {
        tenant = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO tenants (id, name, created_by) VALUES (?, 'jwt test tenant', 'test')"
                        + " ON CONFLICT (id) DO NOTHING",
                tenant);
        jdbc.update("INSERT INTO currencies VALUES ('NGN',2,'Naira') ON CONFLICT (code) DO NOTHING");
    }

    // ------------------------------------------------------------------ tokens

    private static String sign(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static JWTClaimsSet.Builder base() {
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .expirationTime(new Date(System.currentTimeMillis() + 300_000))
                .issueTime(new Date());
    }

    private static String serviceToken(String client) throws Exception {
        return sign(base().subject("service-account-" + client).claim("azp", client).build());
    }

    private String userToken(String username) throws Exception {
        return sign(
                base().subject(username)
                        .claim("azp", "fincore-cli")
                        .claim("tenant_id", tenant.toString())
                        .claim("preferred_username", username)
                        .build());
    }

    // ------------------------------------------------------------------ probes

    private MockHttpServletRequestBuilder asCore(MockHttpServletRequestBuilder builder) throws Exception {
        return builder.header("Authorization", "Bearer " + serviceToken("core"))
                .header(TenantResolver.TENANT_HEADER, tenant.toString())
                .contentType(MediaType.APPLICATION_JSON);
    }

    private String openAccount() throws Exception {
        String body =
                """
                {"idempotencyKey":"%s","type":"CUSTOMER","currency":"NGN","allowNegative":true}
                """
                        .formatted(UUID.randomUUID());
        var result =
                mvc.perform(asCore(post("/v1/accounts")).content(body))
                        .andExpect(status().isCreated())
                        .andReturn();
        String json = result.getResponse().getContentAsString();
        int at = json.indexOf("\"accountId\":\"") + 13;
        return json.substring(at, json.indexOf('"', at));
    }

    @Test
    @DisplayName("no credential is a 401 — deny by default, body-less of reasons")
    void no_credential_is_401() throws Exception {
        mvc.perform(get("/v1/accounts/" + UUID.randomUUID())
                        .header(TenantResolver.TENANT_HEADER, tenant.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a user token presented directly is refused — clients never address the ledger")
    void direct_user_token_is_401() throws Exception {
        mvc.perform(get("/v1/accounts/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + userToken("ada.o")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a service not on the trusted list is refused, valid signature notwithstanding")
    void untrusted_service_is_401() throws Exception {
        mvc.perform(get("/v1/accounts/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + serviceToken("notification"))
                        .header(TenantResolver.TENANT_HEADER, tenant.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("core's credential works, and may assert the tenant for its system jobs")
    void trusted_service_asserts_tenant() throws Exception {
        openAccount(); // 201 asserted inside
    }

    @Test
    @DisplayName("a forwarded user token decides tenant and attribution — verified over asserted")
    void forwarded_token_attributes_the_posting() throws Exception {
        String a = openAccount();
        String b = openAccount();
        String posting =
                """
                {"idempotencyKey":"jwt-attr-1","initiatedBy":"user:someone-else","entries":[
                  {"accountId":"%s","direction":"DEBIT","amountMinor":10000,"currency":"NGN"},
                  {"accountId":"%s","direction":"CREDIT","amountMinor":10000,"currency":"NGN"}]}
                """
                        .formatted(a, b);
        var created =
                mvc.perform(
                                post("/v1/transactions")
                                        .header("Authorization", "Bearer " + serviceToken("core"))
                                        .header("X-Forwarded-Authorization", "Bearer " + userToken("ada.o"))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(posting))
                        .andExpect(status().isCreated())
                        .andReturn();
        String json = created.getResponse().getContentAsString();
        int at = json.indexOf("\"transactionId\":\"") + 17;
        String txId = json.substring(at, json.indexOf('"', at));

        // The record carries the verified principal, not the body's copied string.
        mvc.perform(asCore(get("/v1/transactions/" + txId)))
                .andExpect(jsonPath("$.initiatedBy").value("user:ada.o"));
    }
}
