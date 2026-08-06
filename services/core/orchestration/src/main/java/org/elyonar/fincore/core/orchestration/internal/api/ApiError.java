package org.elyonar.fincore.core.orchestration.internal.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * The error body every Core rejection returns.
 *
 * <p>The same shape the Ledger publishes, for the same reason: a caller branches on {@code code},
 * separates causes that share a code by {@code reason}, and builds whatever sentence its user needs
 * from {@code details}. {@code message} is developer English for a log — never displayed, never
 * parsed, free to be reworded without an amendment.
 *
 * <p>What this replaces was worse than untranslatable. The previous record was
 * {@code (String code, String transactionId)}, and the {@code IllegalArgumentException} handler put
 * {@code e.getMessage()} into {@code code} — so a rejection came back as
 * {@code {"code": "amountMinor must be positive"}} and a caller's switch statement was switching on
 * an English sentence. The same field also carried real codes like {@code WASH_TRANSACTION},
 * depending on which validation happened to fail first.
 *
 * <p>Null and empty members are omitted, so a rejection carrying no parameters stays terse.
 *
 * <p>See {@code docs/conventions/error-contract.md}.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        String code,
        String reason,
        String message,
        boolean retryableWithSameKey,
        String transactionId,
        Map<String, String> details) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, null, message, false, null, Map.of());
    }
}
