package org.elyonar.fincore.identity.auth;

import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.identity.internal.Tx;
import org.springframework.stereotype.Component;

/**
 * The auth audit trail (design.md D11): append-only rows, one per event, dual-attributed. The
 * wire says {@code AUTH_FAILED} in one voice; this table says which voice it actually was — the
 * defender's asymmetry the uniform-refusal decision depends on.
 */
@Component
public class AuthEvents {

    private final Tx tx;
    private final com.fasterxml.jackson.databind.ObjectMapper json =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public AuthEvents(Tx tx) {
        this.tx = tx;
    }

    /** Records inside the caller's already-open tenant transaction. */
    public void record(UUID tenantId, UUID userId, String event, String source, Map<String, Object> details) {
        tx.jdbc()
                .update(
                        "INSERT INTO identity.auth_events (tenant_id, user_id, event, actor_principal,"
                                + " actor_service, source, details) VALUES (?,?,?,?,?,?,?::jsonb)",
                        tenantId,
                        userId,
                        event,
                        userId == null ? null : "user:" + userId,
                        "service:identity",
                        source,
                        write(details));
    }

    /** Tenantless events — service-token issuance has no tenant to be scoped by. */
    public void recordTenantless(String event, String source, Map<String, Object> details) {
        tx.plain(() -> {
            tx.jdbc()
                    .update(
                            "INSERT INTO identity.auth_events (event, actor_service, source, details)"
                                    + " VALUES (?,?,?,?::jsonb)",
                            event,
                            "service:identity",
                            source,
                            write(details));
            return null;
        });
    }

    private String write(Map<String, Object> details) {
        try {
            return json.writeValueAsString(details == null ? Map.of() : details);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}";
        }
    }
}
