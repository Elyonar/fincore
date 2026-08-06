package org.elyonar.fincore.ledger.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * The error body every rejection returns.
 *
 * <p>The split between these fields is what makes the ledger usable outside English. A caller
 * branches on {@code code}, distinguishes causes that share a code by {@code reason}, and builds
 * whatever sentence its user needs from {@code details}. {@code message} is developer English for
 * a log — never displayed, never parsed, and free to be reworded without notice.
 *
 * <p>Without {@code reason} and {@code details} the only description of <em>what</em> was wrong
 * lived in that English sentence, which meant a francophone channel could say no more than
 * "invalid request". A code plus its parameters can be rendered into any language by the party
 * that actually knows the locale — which is never the ledger.
 *
 * <p>{@code retryableWithSameKey} states the contract explicitly rather than leaving Orchestration
 * to infer it from the status: any 4xx is terminal for that key, and a new logical attempt mints
 * a new one.
 *
 * <p>Null and empty members are omitted from the wire, so a rejection carrying no parameters looks
 * exactly as it did before these fields existed.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        String code,
        String reason,
        String message,
        boolean retryableWithSameKey,
        String detail,
        Map<String, String> details) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, null, message, false, null, Map.of());
    }

    public static ApiError of(String code, String message, boolean retryableWithSameKey, String detail) {
        return new ApiError(code, null, message, retryableWithSameKey, detail, Map.of());
    }
}
