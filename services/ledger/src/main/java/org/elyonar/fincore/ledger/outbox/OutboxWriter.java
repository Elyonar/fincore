package org.elyonar.fincore.ledger.outbox;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes domain events into the outbox table, inside the caller's transaction.
 *
 * <p>That last part is the entire design. The event and the state change it describes commit
 * together or not at all, so there is no window in which the ledger has moved money without an
 * event, or published an event for money that never moved. A direct broker publish cannot offer
 * that, whichever order you attempt it in.
 *
 * <p>Payloads are thin — ids plus a minimal summary — because consumers must fetch current state
 * through the read API anyway: the relay guarantees ordering only within a poll batch, so any
 * consumer reconstructing state from event sequence would be wrong. Thin payloads also keep PII
 * out of the bus by construction rather than by review.
 *
 * <p>Monetary values are serialized as decimal strings, matching the read API. Balances are
 * uncapped sums that can exceed 2^53, and a consumer parsing events must not disagree with a
 * consumer parsing responses.
 *
 * <p>Every payload carries {@code ledgerEpoch} for the restore protocol, and {@code outboxId}
 * reaches consumers as the message id or header so they can deduplicate.
 */
@Component
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final LedgerEpoch epoch;

    public OutboxWriter(JdbcTemplate jdbc, LedgerEpoch epoch) {
        this.jdbc = jdbc;
        this.epoch = epoch;
    }

    public void write(UUID tenantId, LedgerEvent event, UUID aggregateId, Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>(payload);
        body.put("tenantId", tenantId.toString());
        body.put("aggregateId", aggregateId.toString());
        // The restore generation. A consumer discards anything from an epoch newer than the one it
        // was told to trust — without it, a post-restore replay is indistinguishable from ordinary
        // at-least-once redelivery.
        body.put("ledgerEpoch", epoch.value());

        jdbc.update(
                "INSERT INTO outbox_events (tenant_id, event_type, aggregate_id, payload)"
                        + " VALUES (?,?,?, CAST(? AS jsonb))",
                tenantId,
                event.wireName(),
                aggregateId.toString(),
                toJson(body));
    }

    /**
     * Minimal JSON, written by hand rather than by a mapper.
     *
     * <p>The payload shape is a published contract with a handful of scalar fields, and a mapper
     * would let it drift with whatever object happened to be passed. Numbers are emitted as
     * strings deliberately — see the class comment.
     */
    private static String toJson(Map<String, Object> body) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> field : body.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(escape(field.getKey())).append("\":");
            Object value = field.getValue();
            if (value == null) {
                json.append("null");
            } else if (value instanceof Boolean b) {
                json.append(b);
            } else if (value instanceof Integer i) {
                json.append(i);
            } else {
                // Longs included: a money value leaves this service as a string, always.
                json.append('"').append(escape(String.valueOf(value))).append('"');
            }
        }
        return json.append('}').toString();
    }

    private static String escape(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
