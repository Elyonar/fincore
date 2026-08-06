package org.elyonar.fincore.core.orchestration.internal.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.UUID;
import org.elyonar.fincore.core.orchestration.api.LedgerOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The outcome protocol, case by case.
 *
 * <p>This is the suite {@code testing.md} names first, because the classification is where the
 * money is lost or created. Each case states what was observed and asserts where it lands — and the
 * transport cases are here in force, since a client library that collapses "connection refused"
 * and "read timeout" into one exception type silently removes the distinction everything else
 * depends on.
 */
class OutcomeClassifierTest {

    private static final UUID TX = UUID.randomUUID();

    // ------------------------------------------------------------------ success

    @Test
    void a_2xx_with_a_transaction_id_is_a_success() {
        assertThat(OutcomeClassifier.ofResponse(201, null, TX))
                .isEqualTo(new LedgerOutcome.Success(TX));
    }

    @Test
    void a_2xx_without_a_transaction_id_is_unknown_not_success() {
        // A success we cannot tie to a transaction would leave a COMPLETED saga with nothing to
        // reconcile against — worse than admitting we do not know.
        assertThat(OutcomeClassifier.ofResponse(200, null, null)).isInstanceOf(LedgerOutcome.Unknown.class);
    }

    // --------------------------------------------------------- definite failure

    @ParameterizedTest
    @ValueSource(
            strings = {
                "INSUFFICIENT_FUNDS",
                "ACCOUNT_CLOSED",
                "UNBALANCED",
                "CURRENCY_MISMATCH",
                "VALUE_DATE_INVALID",
                "WASH_TRANSACTION"
            })
    void every_business_rejection_is_terminal_for_the_key(String errorCode) {
        // The Ledger's contract: a rejection is total — no transaction, no entries, no balance
        // change, no event. So compensation is legal and a retry is not.
        LedgerOutcome outcome = OutcomeClassifier.ofResponse(422, errorCode, null);

        assertThat(outcome).isEqualTo(LedgerOutcome.DefiniteFailure.of(errorCode));
        assertThat(outcome.isUnknown()).isFalse();
    }

    @Test
    void a_4xx_without_an_error_code_still_classifies_as_terminal() {
        assertThat(OutcomeClassifier.ofResponse(400, null, null))
                .isEqualTo(LedgerOutcome.DefiniteFailure.of("HTTP_400"));
    }

    @Test
    void idempotency_key_reuse_is_flagged_as_our_bug() {
        // Our derivation collided with a different payload. Retrying, or minting a new key to route
        // around it, is exactly how a double post happens — so it is marked distinctly.
        LedgerOutcome outcome = OutcomeClassifier.ofResponse(409, "IDEMPOTENCY_KEY_REUSED", null);

        assertThat(outcome).isInstanceOf(LedgerOutcome.DefiniteFailure.class);
        assertThat(((LedgerOutcome.DefiniteFailure) outcome).callerBug()).isTrue();
    }

    // ------------------------------------------------------------------ already

    @Test
    void already_reversed_is_a_success_that_converges_on_the_winner() {
        // Someone else's reversal won. Treating a 409 here as a failure would have the saga
        // retry-loop against a settled outcome.
        assertThat(OutcomeClassifier.ofResponse(409, "ALREADY_REVERSED", TX))
                .isEqualTo(new LedgerOutcome.Success(TX));
    }

    @Test
    void already_reversed_without_the_winners_id_is_unknown() {
        assertThat(OutcomeClassifier.ofResponse(409, "ALREADY_REVERSED", null))
                .isInstanceOf(LedgerOutcome.Unknown.class);
    }

    // ------------------------------------------------------------------ unknown

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504})
    void a_5xx_is_unknown_because_the_ledger_may_have_committed(int status) {
        assertThat(OutcomeClassifier.ofResponse(status, null, null))
                .isInstanceOf(LedgerOutcome.Unknown.class);
    }

    @Test
    void a_read_timeout_is_unknown_because_the_request_was_sent() {
        LedgerOutcome outcome =
                OutcomeClassifier.ofTransportFailure(new SocketTimeoutException("Read timed out"));

        assertThat(outcome).isInstanceOf(LedgerOutcome.Unknown.class);
    }

    @Test
    void a_refused_connection_is_a_definite_failure_because_nothing_was_sent() {
        // The distinction this whole class exists for. Refused means the Ledger never saw the
        // request; timing out means it may have committed. Same "the call failed" to a careless
        // client, opposite meanings here.
        assertThat(OutcomeClassifier.ofTransportFailure(new ConnectException("Connection refused")))
                .isEqualTo(LedgerOutcome.DefiniteFailure.of("LEDGER_UNREACHABLE"));
    }

    @Test
    void an_unresolvable_host_is_also_a_definite_failure() {
        assertThat(OutcomeClassifier.ofTransportFailure(new UnknownHostException("ledger")))
                .isEqualTo(LedgerOutcome.DefiniteFailure.of("LEDGER_UNREACHABLE"));
    }

    @Test
    void a_connection_reset_mid_response_is_unknown() {
        assertThat(OutcomeClassifier.ofTransportFailure(new IOException("Connection reset")))
                .isInstanceOf(LedgerOutcome.Unknown.class);
    }

    @Test
    void the_distinction_survives_being_wrapped() {
        // Client libraries wrap. If unwrapping were skipped, every transport failure would collapse
        // to the default and refused connections would park sagas in ops queues forever.
        assertThat(
                        OutcomeClassifier.ofTransportFailure(
                                new RuntimeException("I/O error", new ConnectException("refused"))))
                .isEqualTo(LedgerOutcome.DefiniteFailure.of("LEDGER_UNREACHABLE"));

        assertThat(
                        OutcomeClassifier.ofTransportFailure(
                                new RuntimeException("I/O error", new SocketTimeoutException("timeout"))))
                .isInstanceOf(LedgerOutcome.Unknown.class);
    }

    @Test
    void anything_unrecognised_defaults_to_unknown() {
        // The safe direction: an unknown is retried against an idempotent API, whereas a wrongly
        // assumed failure is compensated.
        assertThat(OutcomeClassifier.ofTransportFailure(new IllegalStateException("something new")))
                .isInstanceOf(LedgerOutcome.Unknown.class);
    }

    @Test
    void a_success_must_name_its_transaction() {
        assertThat(
                        org.assertj.core.api.Assertions.catchThrowable(
                                () -> new LedgerOutcome.Success(null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
