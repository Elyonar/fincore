package org.elyonar.fincore.ledger.api;

/**
 * The error body every rejection returns.
 *
 * <p>{@code code} is from the published catalog and is what a caller branches on; {@code message}
 * is for a human reading a log. {@code retryableWithSameKey} states the contract explicitly rather
 * than leaving Orchestration to infer it from the status: any 4xx is terminal for that key, and a
 * new logical attempt mints a new one.
 */
public record ApiError(String code, String message, boolean retryableWithSameKey, String detail) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, false, null);
    }
}
