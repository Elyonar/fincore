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

    /**
     * Records inside the caller's already-open tenant transaction — for events that describe
     * something that also committed. A successful login's row belongs with the session it created:
     * if that rolls back, an audit row claiming it happened is worse than none.
     */
    public void record(UUID tenantId, UUID userId, String event, String source, Map<String, Object> details) {
        tx.jdbc()
                .update(
                        "INSERT INTO auth.auth_events (tenant_id, user_id, event, actor_principal,"
                                + " actor_service, source, details) VALUES (?,?,?,?,?,?,?::jsonb)",
                        tenantId,
                        userId,
                        event,
                        userId == null ? null : "user:" + userId,
                        "service:identity",
                        source,
                        write(details));
    }

    /**
     * Records an event a <em>service</em> performed on a <em>human's</em> behalf.
     *
     * <p>{@link #record} attributes to the subject of the event, which is right for authentication
     * — the person logging in is the person the row is about. It is wrong for the directory: the
     * row is about the user being created, while the actor is the administrator who created them,
     * carried here by Core in the forwarded token. ADR 0017 guardrail 4 asks who granted what to
     * whom, and only this overload can answer it. Dual attribution per service-scaffold §6.
     */
    public void recordAs(
            UUID tenantId,
            UUID subjectUserId,
            String event,
            String actorPrincipal,
            String actorService,
            String source,
            Map<String, Object> details) {
        tx.jdbc()
                .update(
                        "INSERT INTO auth.auth_events (tenant_id, user_id, event, actor_principal,"
                                + " actor_service, source, details) VALUES (?,?,?,?,?,?,?::jsonb)",
                        tenantId,
                        subjectUserId,
                        event,
                        actorPrincipal,
                        actorService,
                        source,
                        write(details));
    }

    /**
     * Records a refusal, in its own transaction, so it survives the throw that follows it.
     *
     * <p>Every failing path audits and then throws. Inside the caller's transaction the throw took
     * the audit row with it, so {@code LOGIN_FAILED} — unknown user, bad credential, locked,
     * disabled — was never written at all: the trail recorded successes and nothing else, which is
     * precisely inverted for a service whose reason to keep a trail is the attempts that failed.
     */
    public void recordRefusal(
            UUID tenantId, UUID userId, String event, String source, Map<String, Object> details) {
        tx.independently(tenantId, () -> {
            record(tenantId, userId, event, source, details);
            return null;
        });
    }

    /** Tenantless events — service-token issuance has no tenant to be scoped by. */
    public void recordTenantless(String event, String source, Map<String, Object> details) {
        tx.plain(() -> {
            tx.jdbc()
                    .update(
                            "INSERT INTO auth.auth_events (event, actor_service, source, details)"
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
