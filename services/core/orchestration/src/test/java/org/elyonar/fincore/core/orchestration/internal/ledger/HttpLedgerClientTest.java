package org.elyonar.fincore.core.orchestration.internal.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.core.orchestration.api.LedgerOutcome;
import org.elyonar.fincore.core.orchestration.api.LedgerPosting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The client against a real socket.
 *
 * <p>A mocked HTTP client cannot fail the way a network fails, and the transport half of the
 * outcome protocol is exactly the half that decides whether money is lost or duplicated. So these
 * run against a real server: one that answers, one that stalls, and a port with nothing listening.
 *
 * <p>The two cases this file exists for are {@code a_refused_connection…} and
 * {@code a_stalled_ledger…}. They look identical to a careless client — "the call failed" — and
 * they mean opposite things.
 */
class HttpLedgerClientTest {

    private static final UUID TENANT = UUID.randomUUID();

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                    if (bytes.length > 0) {
                        exchange.getResponseBody().write(bytes);
                    }
                    exchange.close();
                });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** A server that accepts the connection and then never answers. */
    private String startStalledServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static int aClosedPort() throws IOException {
        // Bind and release, so the port is almost certainly free — nothing is listening there.
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static LedgerClient clientFor(String baseUrl) {
        return new HttpLedgerClient(baseUrl, Duration.ofMillis(500), Duration.ofMillis(500), JsonMapper.builder().build());
    }

    private static LedgerPosting aTransfer() {
        return new LedgerPosting(
                "core:" + UUID.randomUUID() + ":post",
                "user:ada.o@branch-01",
                "transfer",
                List.of(
                        new LedgerPosting.Entry(UUID.randomUUID(), LedgerPosting.Direction.DEBIT, 502_000, "NGN"),
                        new LedgerPosting.Entry(UUID.randomUUID(), LedgerPosting.Direction.CREDIT, 500_000, "NGN"),
                        new LedgerPosting.Entry(UUID.randomUUID(), LedgerPosting.Direction.CREDIT, 2_000, "NGN")));
    }

    // ------------------------------------------------------------------ answers

    @Test
    void a_committed_posting_returns_its_transaction_id() throws IOException {
        UUID transactionId = UUID.randomUUID();
        String base = startServer(201, "{\"transactionId\":\"" + transactionId + "\"}");

        assertThat(clientFor(base).post(TENANT, aTransfer()))
                .isEqualTo(new LedgerOutcome.Success(transactionId));
    }

    @Test
    void a_business_rejection_is_terminal_and_carries_the_ledgers_code() throws IOException {
        String base = startServer(422, "{\"code\":\"INSUFFICIENT_FUNDS\"}");

        assertThat(clientFor(base).post(TENANT, aTransfer()))
                .isEqualTo(LedgerOutcome.DefiniteFailure.of("INSUFFICIENT_FUNDS"));
    }

    @Test
    void a_server_error_is_unknown_because_the_ledger_may_have_committed() throws IOException {
        String base = startServer(500, "{\"code\":\"INTERNAL\"}");

        assertThat(clientFor(base).post(TENANT, aTransfer())).isInstanceOf(LedgerOutcome.Unknown.class);
    }

    @Test
    void an_unreadable_success_body_is_unknown_not_success() throws IOException {
        String base = startServer(201, "this is not json");

        assertThat(clientFor(base).post(TENANT, aTransfer())).isInstanceOf(LedgerOutcome.Unknown.class);
    }

    @Test
    void a_success_naming_no_transaction_is_unknown() throws IOException {
        String base = startServer(201, "{\"status\":\"ok\"}");

        assertThat(clientFor(base).post(TENANT, aTransfer())).isInstanceOf(LedgerOutcome.Unknown.class);
    }

    // ---------------------------------------------------------------- transport

    @Test
    void a_refused_connection_is_a_definite_failure_because_nothing_was_sent() throws IOException {
        // Nothing is listening. The request was never written, so the Ledger never saw it — and a
        // ledger that is simply down should fail sagas fast rather than park every one of them in
        // an ops queue awaiting a human.
        LedgerOutcome outcome = clientFor("http://127.0.0.1:" + aClosedPort()).post(TENANT, aTransfer());

        assertThat(outcome).isEqualTo(LedgerOutcome.DefiniteFailure.of("LEDGER_UNREACHABLE"));
    }

    @Test
    void a_stalled_ledger_is_unknown_because_the_request_was_already_sent() throws IOException {
        // The mirror of the case above, and the reason this file uses a real socket: to a careless
        // client both are "the call failed". Here the request reached the Ledger, which may have
        // committed before going quiet — so compensating would be a guess.
        LedgerOutcome outcome = clientFor(startStalledServer()).post(TENANT, aTransfer());

        assertThat(outcome).isInstanceOf(LedgerOutcome.Unknown.class);
    }

    // ------------------------------------------------------------------ reversal

    @Test
    void already_reversed_converges_on_the_winning_reversal() throws IOException {
        UUID winner = UUID.randomUUID();
        String base = startServer(409, "{\"code\":\"ALREADY_REVERSED\",\"reversalTransactionId\":\"" + winner + "\"}");

        assertThat(clientFor(base).reverse(TENANT, UUID.randomUUID(), "core:x:reverse", "user:ada"))
                .isEqualTo(new LedgerOutcome.Success(winner));
    }

    // ------------------------------------------------------------------- our bug

    @Test
    void an_unbalanced_posting_is_our_defect_and_never_reaches_the_ledger() throws IOException {
        String base = startServer(201, "{\"transactionId\":\"" + UUID.randomUUID() + "\"}");
        LedgerPosting unbalanced =
                new LedgerPosting(
                        "core:x:post",
                        "user:ada",
                        "bad",
                        List.of(
                                new LedgerPosting.Entry(UUID.randomUUID(), LedgerPosting.Direction.DEBIT, 100, "NGN"),
                                new LedgerPosting.Entry(UUID.randomUUID(), LedgerPosting.Direction.CREDIT, 99, "NGN")));

        assertThat(
                        org.assertj.core.api.Assertions.catchThrowable(
                                () -> clientFor(base).post(TENANT, unbalanced)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not balance");
    }
}
