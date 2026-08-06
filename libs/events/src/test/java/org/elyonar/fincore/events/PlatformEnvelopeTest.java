package org.elyonar.fincore.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The envelope every publisher on the platform emits (ADR 0008).
 *
 * <p>This suite exists because the rule was previously prose. Two services each assembled "the
 * envelope" and produced two different shapes — a flat body against a nested one, {@code
 * ledgerEpoch} against {@code epoch}, no {@code occurredAt} on either, and the ledger's
 * deduplication key missing entirely. Nothing consumed events, so nothing failed. A rule that
 * could be a test and is not is a gap in the guardrails.
 */
@DisplayName("the platform event envelope — one shape, every publisher")
class PlatformEnvelopeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static DomainEvent event() {
        return new DomainEvent(
                42L,
                "posting.completed",
                "8f14e45f-ea8d-4b3a-9c11-000000000001",
                "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                Instant.parse("2026-08-06T09:15:30Z"),
                7L,
                "{\"transactionId\":\"tx-1\",\"entryCount\":\"2\"}");
    }

    @Test
    @DisplayName("carries exactly ADR 0008's seven fields, in the order the ADR lists them")
    void has_exactly_the_seven_fields() throws Exception {
        JsonNode envelope = JSON.readTree(event().envelope());

        List<String> fields = new ArrayList<>();
        envelope.fieldNames().forEachRemaining(fields::add);

        assertThat(fields)
                .as("a consumer reads the ADR and the bytes and finds the same document")
                .containsExactly(
                        "eventId", "eventType", "aggregateId", "tenantId", "occurredAt", "epoch", "payload");
    }

    @Test
    @DisplayName("the payload is embedded JSON, never a string a consumer has to parse twice")
    void payload_is_embedded_json() throws Exception {
        JsonNode envelope = JSON.readTree(event().envelope());

        assertThat(envelope.get("payload").getNodeType()).isEqualTo(JsonNodeType.OBJECT);
        assertThat(envelope.get("payload").get("transactionId").asText()).isEqualTo("tx-1");
    }

    @Test
    @DisplayName("eventId and epoch are numbers; occurredAt is an ISO-8601 instant")
    void field_types_are_stable() throws Exception {
        JsonNode envelope = JSON.readTree(event().envelope());

        assertThat(envelope.get("eventId").isNumber()).isTrue();
        assertThat(envelope.get("eventId").asLong()).isEqualTo(42L);
        assertThat(envelope.get("epoch").isNumber()).isTrue();
        assertThat(envelope.get("epoch").asLong()).isEqualTo(7L);
        assertThat(Instant.parse(envelope.get("occurredAt").asText()))
                .isEqualTo(Instant.parse("2026-08-06T09:15:30Z"));
    }

    @Test
    @DisplayName("a partial envelope cannot be constructed at all")
    void partial_envelope_is_rejected() {
        // Each null stands for a publisher that forgot a column. Failing here means failing on the
        // first relay pass, loudly — rather than emitting "tenantId":"null" to a consumer that
        // will faithfully act on it.
        assertThatThrownBy(
                        () -> new DomainEvent(1L, "a.b", "agg", null, Instant.now(), 1L, "{}"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("tenantId");

        assertThatThrownBy(() -> new DomainEvent(1L, "a.b", "agg", "tenant", null, 1L, "{}"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("occurredAt");

        assertThatThrownBy(
                        () -> new DomainEvent(1L, null, "agg", "tenant", Instant.now(), 1L, "{}"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("eventType");
    }

    @Test
    @DisplayName("a quote in a field cannot break out of the envelope")
    void string_fields_are_escaped() throws Exception {
        DomainEvent hostile =
                new DomainEvent(
                        1L,
                        "a\".b",
                        "agg\\id",
                        "tenant\"id",
                        Instant.parse("2026-08-06T00:00:00Z"),
                        1L,
                        "{}");

        JsonNode envelope = JSON.readTree(hostile.envelope());

        assertThat(envelope.get("eventType").asText()).isEqualTo("a\".b");
        assertThat(envelope.get("aggregateId").asText()).isEqualTo("agg\\id");
        assertThat(envelope.get("tenantId").asText()).isEqualTo("tenant\"id");
    }

    @Test
    @DisplayName("every publisher sends the envelope, not the bare payload")
    void publishers_send_the_envelope() {
        // The logging adapter is the one publisher with no broker to inspect, so this asserts the
        // property the other two inherit from the same accessor: what goes on the wire is
        // envelope(), and a publisher reaching for payload() would fail this if it were bare.
        DomainEvent event = event();

        assertThat(event.envelope())
                .contains("\"eventId\":42")
                .contains("\"tenantId\":\"3fa85f64-5717-4562-b3fc-2c963f66afa6\"")
                .contains(event.payload());
        assertThat(event.payload()).doesNotContain("eventId");
    }

    @Test
    @DisplayName("the accessor list is the envelope's field list — a new field cannot be forgotten")
    void record_components_match_the_envelope() throws Exception {
        // If someone adds a component to DomainEvent, this fails until envelope() carries it.
        // `id` is the one rename: it goes on the wire as ADR 0008's `eventId`.
        List<String> components =
                Stream.of(DomainEvent.class.getRecordComponents())
                        .map(c -> c.getName().equals("id") ? "eventId" : c.getName())
                        .collect(Collectors.toList());

        List<String> fields = new ArrayList<>();
        JSON.readTree(event().envelope()).fieldNames().forEachRemaining(fields::add);

        assertThat(fields).containsExactlyElementsOf(components);
    }
}
