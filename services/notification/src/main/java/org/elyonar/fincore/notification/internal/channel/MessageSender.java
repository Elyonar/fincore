package org.elyonar.fincore.notification.internal.channel;

import java.util.Map;
import java.util.UUID;

/**
 * Hands a rendered message to whatever carries it off this service.
 *
 * <p>One implementation per channel and backend. The connector-backed ones arrive with the
 * messaging connector, which holds the per-tenant sender ids and credentials — this service never
 * does (PRD §4.6), and no provider SDK appears in its POM.
 *
 * <p><strong>The three-valued outcome is load-bearing</strong>, and it is the same distinction the
 * money path makes for the same reason. A gateway that times out has not failed: the message may
 * well have gone. Collapsing that into failure produces a second SMS; collapsing it into success
 * produces a customer who was never told their account moved. Only {@code DEFINITE_FAILURE} —
 * a malformed number, a rejected sender id — is terminal.
 */
public interface MessageSender {

    /** The channel id this sender serves, matching a row in {@code notification.channels}. */
    String channel();

    /** Whether this actually delivers anywhere. The logging adapter answers false, loudly. */
    boolean delivers();

    /**
     * @param clientReference derived from the notification and attempt number, never random, so a
     *     gateway that supports deduplication can apply it across a retry
     * @param parts the rendered template parts — {@code body}, and {@code subject} where the
     *     channel requires one
     */
    Result send(UUID notificationId, String address, Map<String, String> parts, String clientReference);

    /**
     * @param gatewayRef the provider's own id for the message, where it returns one — what an
     *     operator quotes when asking a gateway what happened
     * @param errorCode machine-readable, never English prose (hard rule 9)
     */
    record Result(Outcome outcome, String gatewayRef, String errorCode) {

        public static Result sent(String gatewayRef) {
            return new Result(Outcome.SENT, gatewayRef, null);
        }

        public static Result rejected(String errorCode) {
            return new Result(Outcome.DEFINITE_FAILURE, null, errorCode);
        }

        public static Result unknown(String errorCode) {
            return new Result(Outcome.UNKNOWN, null, errorCode);
        }
    }

    enum Outcome {
        SENT,
        DEFINITE_FAILURE,
        UNKNOWN
    }
}
