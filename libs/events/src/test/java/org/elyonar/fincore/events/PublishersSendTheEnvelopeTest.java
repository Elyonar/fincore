package org.elyonar.fincore.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * What actually goes on the wire is the envelope.
 *
 * <p>{@link PlatformEnvelopeTest} proves the envelope is well formed; this proves a publisher
 * sends it. The distinction is the whole bug: for months the renderer existed on one side and the
 * publishers reached past it for the bare payload, so the deduplication key and the tenant never
 * left the process. A test on the record alone would have passed throughout.
 */
@DisplayName("publishers put the envelope on the wire, never the bare payload")
class PublishersSendTheEnvelopeTest {

    private static final String PAYLOAD = "{\"transactionId\":\"tx-1\"}";

    private static DomainEvent event() {
        return new DomainEvent(
                99L,
                "transfer.completed",
                "agg-1",
                "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                Instant.parse("2026-08-06T09:15:30Z"),
                3L,
                PAYLOAD);
    }

    @Test
    @DisplayName("Kafka: the message body is the envelope, the key is the aggregate id")
    @SuppressWarnings("unchecked")
    void kafka_sends_the_envelope() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        List<Long> acknowledged =
                new KafkaEventPublisher(kafka, "fincore.core").publish(List.of(event()));

        ArgumentCaptor<String> topic = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(topic.capture(), key.capture(), body.capture());

        assertThat(topic.getValue())
                .as("fincore.<publisher>.<eventType>, per ADR 0008")
                .isEqualTo("fincore.core.transfer.completed");
        assertThat(key.getValue())
                .as("keyed by aggregate — the only ordering the platform promises")
                .isEqualTo("agg-1");
        assertThat(body.getValue())
                .as("the envelope, not the payload")
                .isEqualTo(event().envelope())
                .contains("\"eventId\":99")
                .contains("\"occurredAt\":\"2026-08-06T09:15:30Z\"")
                .contains("\"epoch\":3");
        assertThat(body.getValue()).isNotEqualTo(PAYLOAD);
        assertThat(acknowledged).containsExactly(99L);
    }

    @Test
    @DisplayName("an unacknowledged send is not reported as delivered")
    @SuppressWarnings("unchecked")
    void kafka_does_not_acknowledge_a_failed_send() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        List<Long> acknowledged =
                new KafkaEventPublisher(kafka, "fincore.core").publish(List.of(event()));

        assertThat(acknowledged)
                .as("the row stays pending and is retried; marking it published would lose it")
                .isEmpty();
    }
}
