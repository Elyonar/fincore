package org.elyonar.fincore.notification.internal.intake;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One message off the bus, in the platform envelope of ADR 0008.
 *
 * <p>Every field is required, and parsing refuses a message missing any of them. That is not
 * defensive habit: the envelope was rendered by two services independently until recently and they
 * disagreed — the ledger emitted no {@code eventId} at all and neither publisher emitted
 * {@code occurredAt}, which are respectively the deduplication key and the staleness guard this
 * consumer depends on. A consumer that quietly defaulted a missing field would have hidden that,
 * and hidden it in the direction of sending messages it should not.
 *
 * @param payload the publisher's thin domain payload, flattened to strings for template variables.
 *     Money arrives as a decimal string and stays one — a consumer parsing it as a number is how a
 *     kobo goes missing between two services that agreed on everything else.
 */
public record EventEnvelope(
        long eventId,
        String eventType,
        String aggregateId,
        UUID tenantId,
        Instant occurredAt,
        long epoch,
        Map<String, String> payload) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static EventEnvelope parse(String json) {
        JsonNode root = JSON.readTree(json);

        Map<String, String> payload = new LinkedHashMap<>();
        JsonNode body = require(root, "payload");
        body.properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            // Scalars only. A nested object in a template variable renders as JSON in an SMS,
            // which is a worse outcome than the template failing to render at all.
            if (value.isValueNode()) {
                payload.put(entry.getKey(), value.asString());
            }
        });

        return new EventEnvelope(
                require(root, "eventId").asLong(),
                require(root, "eventType").asString(),
                require(root, "aggregateId").asString(),
                UUID.fromString(require(root, "tenantId").asString()),
                Instant.parse(require(root, "occurredAt").asString()),
                require(root, "epoch").asLong(),
                payload);
    }

    private static JsonNode require(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            throw new MalformedEnvelope(field);
        }
        return value;
    }

    /**
     * The business moment this event describes.
     *
     * <p>Not the event id. Two different events can describe one moment — the ledger's
     * {@code posting.completed} and Core's {@code transfer.completed} are the standing example —
     * and deduplicating on the event id cannot see that, because they really are different events.
     * The type and the aggregate together name the moment itself.
     */
    public String businessMomentKey() {
        return eventType + ":" + aggregateId;
    }

    public static class MalformedEnvelope extends RuntimeException {
        public MalformedEnvelope(String field) {
            super("event envelope is missing " + field + " (ADR 0008)");
        }
    }
}
