package org.elyonar.fincore.core.lending.internal.outbox;

import java.util.UUID;
import org.elyonar.fincore.core.lending.api.LendingBeans;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Lending's outbox writer — same transaction as the state change, so a {@code loan.*} event
 * exists if and only if the change committed (ADR 0008). The shared relay polls this table
 * alongside orchestration's.
 */
@Component
public class LendingOutbox {

    private final JdbcTemplate jdbc;

    public LendingOutbox(@Qualifier(LendingBeans.JDBC) JdbcTemplate lendingJdbcTemplate) {
        this.jdbc = lendingJdbcTemplate;
    }

    /** Must be called inside a lending transaction with the tenant context set. */
    public void append(UUID tenantId, String eventType, UUID aggregateId, String payloadJson) {
        jdbc.update(
                "INSERT INTO lending.outbox_events (tenant_id, event_type, aggregate_id, payload)"
                        + " VALUES (?,?,?,?::jsonb)",
                tenantId,
                eventType,
                aggregateId.toString(),
                payloadJson);
    }
}
