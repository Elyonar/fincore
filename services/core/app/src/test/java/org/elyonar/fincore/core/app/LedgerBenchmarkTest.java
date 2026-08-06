package org.elyonar.fincore.core.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Measures what the platform has only ever claimed.
 *
 * <p>{@code architecture.md} states a posting-commit target of p99 &lt; 200 ms and 200 TPS per
 * tenant cluster. Those were written as targets and never measured, which is a specific kind of
 * dishonesty — a number in a document reads as a fact. This produces real ones.
 *
 * <p><strong>What this is not.</strong> It runs on whatever machine invokes it, against a
 * single-container database sharing a laptop with the JVM issuing the load. It is a floor, not a
 * capacity plan: numbers below the target here mean something is wrong, numbers above it do not
 * prove the target holds on reference hardware. It is also a few seconds of load, so it says
 * nothing about soak behaviour — connection leaks, memory growth and index bloat all need hours.
 *
 * <pre>
 *   docker compose up -d ledger
 *   ./mvnw -pl services/core/app -am -Pcontract test -Dtest=LedgerBenchmarkTest \
 *          -Dfincore.contract.tenant-id=&lt;tenant&gt;
 * </pre>
 */
@Tag("contract")
class LedgerBenchmarkTest {

    private static final String LEDGER =
            System.getProperty("fincore.contract.ledger-url", "http://localhost:58080");
    private static final int WARMUP = 50;
    private static final int SAMPLES = 400;
    private static final int CONCURRENCY = 8;

    private static final HttpClient http = HttpClient.newHttpClient();
    private static UUID tenantId;
    private static UUID from;
    private static UUID to;

    @BeforeAll
    static void provision() {
        tenantId = UUID.fromString(System.getProperty("fincore.contract.tenant-id", ""));
        from = account("INTERNAL", true);
        to = account("CUSTOMER", false);
    }

    private static UUID account(String type, boolean allowNegative) {
        String body =
                "{\"idempotencyKey\":\"bench-" + UUID.randomUUID() + "\",\"type\":\"" + type
                        + "\",\"currency\":\"NGN\",\"allowNegative\":" + allowNegative
                        + ",\"customerRef\":\"bench\"}";
        return UUID.fromString(field(post("/v1/accounts", body).body(), "accountId"));
    }

    private static HttpResponse<String> post(String path, String body) {
        try {
            return http.send(
                    HttpRequest.newBuilder(URI.create(LEDGER + path))
                            .header("Content-Type", "application/json")
                            .header("X-Tenant-Id", tenantId.toString())
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("Ledger at " + LEDGER + " unreachable", e);
        }
    }

    private static String field(String json, String name) {
        int at = json.indexOf("\"" + name + "\":\"") + name.length() + 4;
        return json.substring(at, json.indexOf('"', at));
    }

    /** One posting, returning how long the round trip took in microseconds. */
    private static long postOnce() {
        String body =
                "{\"idempotencyKey\":\"bench-" + UUID.randomUUID() + "\",\"initiatedBy\":\"bench\","
                        + "\"entries\":[{\"accountId\":\"" + from + "\",\"direction\":\"DEBIT\","
                        + "\"amountMinor\":\"100\",\"currency\":\"NGN\"},{\"accountId\":\"" + to
                        + "\",\"direction\":\"CREDIT\",\"amountMinor\":\"100\",\"currency\":\"NGN\"}]}";
        long started = System.nanoTime();
        HttpResponse<String> response = post("/v1/transactions", body);
        long elapsed = (System.nanoTime() - started) / 1_000;
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("posting failed: " + response.statusCode() + " " + response.body());
        }
        return elapsed;
    }

    private static long percentile(List<Long> sorted, double p) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1));
    }

    @Test
    void posting_latency_and_throughput_are_measured_not_assumed() throws Exception {
        for (int i = 0; i < WARMUP; i++) {
            postOnce(); // JIT, connection pools and page cache, so the samples measure steady state
        }

        List<Long> samples = Collections.synchronizedList(new ArrayList<>(SAMPLES));
        long started = System.nanoTime();

        try (ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY)) {
            List<Callable<Void>> work = new ArrayList<>(SAMPLES);
            for (int i = 0; i < SAMPLES; i++) {
                work.add(
                        () -> {
                            samples.add(postOnce());
                            return null;
                        });
            }
            for (var future : pool.invokeAll(work)) {
                future.get();
            }
        }

        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);

        double tps = SAMPLES / seconds;
        long p50 = percentile(sorted, 0.50);
        long p95 = percentile(sorted, 0.95);
        long p99 = percentile(sorted, 0.99);

        System.out.printf(
                "%n  ── ledger posting benchmark ──────────────────────────────%n"
                        + "  samples      %d at concurrency %d%n"
                        + "  throughput   %.0f postings/sec%n"
                        + "  latency      p50 %.1f ms   p95 %.1f ms   p99 %.1f ms   max %.1f ms%n"
                        + "  ──────────────────────────────────────────────────────────%n"
                        + "  Laptop, single-container database, load from the same machine.%n"
                        + "  A floor, not a capacity plan — and no evidence about soak.%n%n",
                SAMPLES, CONCURRENCY, tps,
                p50 / 1000.0, p95 / 1000.0, p99 / 1000.0, sorted.get(sorted.size() - 1) / 1000.0);

        // Asserted loosely and deliberately. A tight bound on shared developer hardware fails for
        // reasons that have nothing to do with the ledger, and a suite that cries wolf gets muted.
        // This catches an order-of-magnitude regression, which is the thing worth catching here.
        assertThat(p99)
                .as("p99 posting latency, microseconds — architecture.md targets under 200ms")
                .isLessThan(2_000_000L);
        assertThat(tps).as("postings per second").isGreaterThan(10.0);
    }
}
