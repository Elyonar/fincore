package org.elyonar.fincore.core.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The pricing authoring surface, over real HTTP — the tests the surface shipped without.
 *
 * <p>Every case here is a defect that survived its first audit precisely because nothing exercised
 * these routes. The centre of gravity is the same as {@link ProductApiTest}'s: maker-checker on
 * publish is a money control, and everything that can quietly hollow it out — a draft attributed to
 * somebody who did not write it, a rule slid in after the checker read the version, a 500% fee that
 * parses — gets a test that has to be deleted, visibly, before the control can regress.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PricingAuthoringApiTest {

    @Autowired private TenantRegistry tenantRegistry;

    @Autowired
    @Qualifier("productJdbcTemplate")
    private JdbcTemplate productDb;

    @Autowired
    @Qualifier("productTransactionManager")
    private PlatformTransactionManager productTx;

    @Autowired
    @Qualifier("orchestrationJdbcTemplate")
    private JdbcTemplate orchestrationDb;

    @Autowired
    @Qualifier("orchestrationTransactionManager")
    private PlatformTransactionManager orchestrationTx;

    @LocalServerPort private int port;
    private final HttpClient http = HttpClient.newHttpClient();

    private UUID tenantId;

    /**
     * A fee-income account in the register, planted directly.
     *
     * <p>The controller verifies a rule's account against the internal-accounts register, not the
     * ledger, so the register row is all a pricing test needs — no stub ledger required.
     */
    private UUID feeIncomeAccount() {
        UUID ledgerAccountId = UUID.randomUUID();
        new TransactionTemplate(orchestrationTx)
                .executeWithoutResult(s -> {
                    orchestrationDb.queryForObject(
                            "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
                    orchestrationDb.update(
                            "INSERT INTO orchestration.internal_accounts"
                                    + " (tenant_id, ledger_account_id, code, name, purpose, currency, opened_by)"
                                    + " VALUES (?,?,?, 'Fee income', 'FEE_INCOME', 'NGN', 'user:test')",
                            tenantId, ledgerAccountId, "FEES-" + UUID.randomUUID().toString().substring(0, 8));
                });
        return ledgerAccountId;
    }

    @DynamicPropertySource
    static void quiet(DynamicPropertyRegistry registry) {
        registry.add("fincore.core.worker.interval-ms", () -> "3600000");
        registry.add("fincore.core.outbox.relay.interval-ms", () -> "3600000");
    }

    @BeforeEach
    void freshTenant() {
        tenantId = UUID.randomUUID();
        tenantRegistry.register(tenantId, "test tenant", "test");
    }

    private HttpRequest.Builder as(String path, String permissions, String principal) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Dev-Tenant-Id", tenantId.toString())
                .header("X-Dev-Principal", principal)
                .header("X-Dev-Permissions", permissions);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID createProduct(String author) {
        HttpResponse<String> created = send(
                as("/v1/products", "products:create", author)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"code\":\"SAV-" + UUID.randomUUID() + "\",\"name\":\"Everyday\",\"type\":\"SAVINGS\"}"))
                        .build());
        assertThat(created.statusCode()).isEqualTo(201);
        String body = created.body();
        int at = body.indexOf("\"productId\":\"") + 13;
        return UUID.fromString(body.substring(at, body.indexOf('"', at)));
    }

    private HttpResponse<String> draft(UUID productId, String author) {
        return send(
                as("/v1/products/" + productId + "/versions", "products:create", author)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"copyFrom\":null}"))
                        .build());
    }

    private HttpResponse<String> putLimitRules(UUID productId, int version, String rulesJson, String author) {
        return send(
                as("/v1/products/" + productId + "/versions/" + version + "/limit-rules", "products:create", author)
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"rules\":" + rulesJson + "}"))
                        .build());
    }

    private HttpResponse<String> publish(UUID productId, int version, String principal) {
        return send(
                as("/v1/products/" + productId + "/versions/" + version + "/publish", "products:publish", principal)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build());
    }

    // ------------------------------------------------------------------ attribution

    @Test
    void a_draft_belongs_to_the_person_who_drafted_it() {
        // A created the product (and so authored v1). B drafts v2. The record must say B —
        // under the old inherited-attribution bug it said A, which let B publish B's own
        // pricing and refused A, inverting maker-checker from version 2 on.
        UUID productId = createProduct("user:alice");
        HttpResponse<String> drafted = draft(productId, "user:bob");
        assertThat(drafted.statusCode()).isEqualTo(201);
        assertThat(drafted.body()).contains("\"version\":2");

        // Bob wrote it, so Bob may not make it live.
        HttpResponse<String> bobPublishes = publish(productId, 2, "user:bob");
        assertThat(bobPublishes.statusCode()).isEqualTo(403);
        assertThat(bobPublishes.body()).contains("PUBLISHER_IS_AUTHOR");

        // Alice did not write v2, so Alice is exactly who may.
        HttpResponse<String> alicePublishes = publish(productId, 2, "user:alice");
        assertThat(alicePublishes.statusCode()).isEqualTo(200);
        assertThat(alicePublishes.body()).contains("\"publishedBy\":\"user:alice\"");
        assertThat(alicePublishes.body()).contains("\"createdBy\":\"user:bob\"");
    }

    // ------------------------------------------------------------------ rule shape

    @Test
    void a_fee_above_one_hundred_percent_is_refused_with_a_reason() {
        UUID productId = createProduct("user:alice");
        assertThat(draft(productId, "user:bob").statusCode()).isEqualTo(201);

        // 50,000 basis points is a 500% fee — a typed zero too many, and until the shape checks
        // were rescued from the dead validation stack it was storable, publishable and charged.
        // The account is real and valid, so the only thing wrong with this rule is the rate.
        HttpResponse<String> refused = send(
                as("/v1/products/" + productId + "/versions/2/fee-rules", "products:create", "user:bob")
                        .PUT(HttpRequest.BodyPublishers.ofString(
                                "{\"rules\":[{\"operation\":\"DEPOSIT\",\"kind\":\"PERCENT\","
                                        + "\"basisPoints\":50000,\"currency\":\"NGN\","
                                        + "\"feeAccountId\":\"" + feeIncomeAccount() + "\"}]}"))
                        .build());

        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(refused.body()).contains("RULES_INVALID").contains("RATE_OUT_OF_RANGE");
    }

    @Test
    void a_limit_for_a_tier_nobody_can_hold_is_refused_not_stored() {
        UUID productId = createProduct("user:alice");
        assertThat(draft(productId, "user:bob").statusCode()).isEqualTo(201);

        // The evaluator denies by default, so a rule for "TIER1" (no underscore) would never match
        // and the product would silently refuse every TIER_1 transaction. Refusal beats silence.
        HttpResponse<String> typoTier = putLimitRules(
                productId, 2,
                "[{\"kycTier\":\"TIER1\",\"channel\":\"TELLER\",\"limitType\":\"PER_TXN\","
                        + "\"maxAmountMinor\":1000000,\"currency\":\"NGN\"}]",
                "user:bob");
        assertThat(typoTier.statusCode()).isEqualTo(422);
        assertThat(typoTier.body()).contains("RULES_INVALID").contains("UNKNOWN_KYC_TIER");

        HttpResponse<String> typoChannel = putLimitRules(
                productId, 2,
                "[{\"kycTier\":\"TIER_1\",\"channel\":\"USSD\",\"limitType\":\"PER_TXN\","
                        + "\"maxAmountMinor\":1000000,\"currency\":\"NGN\"}]",
                "user:bob");
        assertThat(typoChannel.statusCode()).isEqualTo(422);
        assertThat(typoChannel.body()).contains("UNKNOWN_CHANNEL");
    }

    // ------------------------------------------------------------------ effective dating

    @Test
    void backdating_is_refused_in_every_spelling_the_database_would_accept() {
        UUID productId = createProduct("user:alice");
        assertThat(draft(productId, "user:bob").statusCode()).isEqualTo(201);

        // The ISO spelling was always caught.
        HttpResponse<String> isoPast = schedule(productId, 2, "2020-01-01T00:00:00Z");
        assertThat(isoPast.statusCode()).isEqualTo(422);
        assertThat(isoPast.body()).contains("EFFECTIVE_FROM_IN_THE_PAST");

        // These two were the bypass: Java's parsers refuse them, Postgres's timestamptz cast does
        // not, and the old guard waved through whatever it could not read. Now the guard is the
        // authority — what it cannot parse, nobody downstream ever sees.
        for (String spelling : new String[] {"2020-01-01", "2020-01-01 00:00:00+00"}) {
            HttpResponse<String> refused = schedule(productId, 2, spelling);
            assertThat(refused.statusCode()).as("spelling: " + spelling).isEqualTo(422);
            assertThat(refused.body()).contains("RULES_INVALID").contains("EFFECTIVE_FROM_INVALID");
        }

        // Forward-dating still works.
        HttpResponse<String> future = schedule(productId, 2, "2099-01-01T00:00:00Z");
        assertThat(future.statusCode()).isEqualTo(200);
        assertThat(future.body()).contains("2099-01-01T00:00:00Z");
    }

    private HttpResponse<String> schedule(UUID productId, int version, String effectiveFrom) {
        return send(
                as("/v1/products/" + productId + "/versions/" + version, "products:create", "user:bob")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(
                                "{\"effectiveFrom\":\"" + effectiveFrom + "\"}"))
                        .build());
    }

    // ------------------------------------------------------------------ publish revalidation

    @Test
    void a_version_whose_fee_rule_names_no_account_cannot_be_published() {
        UUID productId = createProduct("user:alice");
        assertThat(draft(productId, "user:bob").statusCode()).isEqualTo(201);

        // The API refuses a null fee account at authoring, so plant the row the way legacy data
        // or a direct write would have: straight into the table, past the surface.
        new TransactionTemplate(productTx)
                .executeWithoutResult(s -> {
                    productDb.queryForObject(
                            "SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
                    productDb.update(
                            "INSERT INTO product.fee_rules"
                                    + " (tenant_id, product_version_id, operation, kind, flat_minor, currency)"
                                    + " SELECT tenant_id, id, 'DEPOSIT', 'FLAT', 5000, 'NGN'"
                                    + "   FROM product.product_versions WHERE tenant_id = ? AND version = 2",
                            tenantId);
                });

        // Publish is the last gate before the money path, where this row would otherwise surface
        // one stranded transaction at a time, each burning daily-limit headroom.
        HttpResponse<String> refused = publish(productId, 2, "user:alice");
        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(refused.body()).contains("PRICING_ACCOUNT_INVALID");
    }

    // ------------------------------------------------------------------ publish vs edit race

    @Test
    void a_rule_write_racing_a_publish_cannot_land_unsigned() throws Exception {
        UUID productId = createProduct("user:alice");
        assertThat(draft(productId, "user:bob").statusCode()).isEqualTo(201);
        assertThat(putLimitRules(
                        productId, 2,
                        "[{\"kycTier\":\"TIER_1\",\"channel\":\"TELLER\",\"limitType\":\"PER_TXN\","
                                + "\"maxAmountMinor\":1000000,\"currency\":\"NGN\"}]",
                        "user:bob")
                        .statusCode())
                .isEqualTo(200);

        // Publish and a rule write, released together. The version row lock serialises them:
        // whichever runs second sees the first's verdict — the write meets VERSION_NOT_DRAFT, or
        // the publish signs rules that were fully written. What must be impossible is the third
        // outcome the lock exists to forbid: both succeeding, leaving a live version carrying
        // rules the checker never read.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 8; round++) {
                UUID roundProduct = createProduct("user:alice");
                assertThat(draft(roundProduct, "user:bob").statusCode()).isEqualTo(201);

                CountDownLatch go = new CountDownLatch(1);
                Future<HttpResponse<String>> publishing = pool.submit(() -> {
                    go.await();
                    return publish(roundProduct, 2, "user:alice");
                });
                Future<HttpResponse<String>> writing = pool.submit(() -> {
                    go.await();
                    return putLimitRules(
                            roundProduct, 2,
                            "[{\"kycTier\":\"TIER_2\",\"channel\":\"API\",\"limitType\":\"DAILY\","
                                    + "\"maxAmountMinor\":42,\"currency\":\"NGN\"}]",
                            "user:bob");
                });
                go.countDown();
                HttpResponse<String> published = publishing.get(30, TimeUnit.SECONDS);
                HttpResponse<String> wrote = writing.get(30, TimeUnit.SECONDS);

                assertThat(published.statusCode()).isEqualTo(200);
                if (wrote.statusCode() == 200) {
                    // The write won the lock and finished before publish read the version. Then
                    // the published rules must be exactly what was written — signed, not slid in.
                    HttpResponse<String> readBack = send(
                            as("/v1/products/" + roundProduct + "/versions/2", "products:read", "user:carol")
                                    .GET()
                                    .build());
                    assertThat(readBack.body()).contains("\"maxAmountMinor\":42");
                } else {
                    // Publish won: the write must have been refused, not silently absorbed.
                    assertThat(wrote.statusCode()).isEqualTo(409);
                    assertThat(wrote.body()).contains("VERSION_ALREADY_PUBLISHED");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ concurrent drafts

    @Test
    void two_drafts_of_the_same_next_version_get_one_winner_and_one_409() throws Exception {
        UUID productId = createProduct("user:alice");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            Future<HttpResponse<String>> first = pool.submit(() -> {
                go.await();
                return draft(productId, "user:bob");
            });
            Future<HttpResponse<String>> second = pool.submit(() -> {
                go.await();
                return draft(productId, "user:carol");
            });
            go.countDown();
            int a = first.get(30, TimeUnit.SECONDS).statusCode();
            int b = second.get(30, TimeUnit.SECONDS).statusCode();

            // Either both landed sequentially (2 then 3) or the race was real and the loser got
            // the 409 whose remedy is retry — never the raw constraint violation as a 500.
            assertThat(a).isIn(201, 409);
            assertThat(b).isIn(201, 409);
            assertThat(a == 500 || b == 500).isFalse();
            if (a == 409 || b == 409) {
                String loser = (a == 409 ? first : second).get().body();
                assertThat(loser).contains("DRAFT_CONFLICT");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
