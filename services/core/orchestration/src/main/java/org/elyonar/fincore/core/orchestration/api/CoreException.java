package org.elyonar.fincore.core.orchestration.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A refusal carrying a contract error code and the machine-readable facts behind it.
 *
 * <p>Same contract as the Ledger's {@code LedgerException}, and deliberately the same shape:
 * {@link #errorCode()} is what a caller branches on, {@link #reason()} separates causes that share
 * a code, {@link #details()} carries the parameters a translated message interpolates, and the
 * message is developer English that is never displayed and never parsed.
 *
 * <p>This replaces two things that were not contracts at all. Command validation threw
 * {@code IllegalArgumentException("amountMinor must be positive")} and the HTTP layer put that
 * sentence in the {@code code} field, so a caller branching on the code was branching on English
 * prose — and the same path also carried real codes like {@code WASH_TRANSACTION}, so the field
 * held two different kinds of thing depending on which validation failed.
 *
 * <p>See {@code docs/conventions/error-contract.md}.
 */
public class CoreException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String reason;
    private final Map<String, String> details;

    public CoreException(ErrorCode errorCode, String message) {
        this(errorCode, null, message, Map.of());
    }

    public CoreException(ErrorCode errorCode, String reason, String message, Map<String, String> details) {
        super(message);
        this.errorCode = errorCode;
        this.reason = reason;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** Sub-classification where one code spans several causes. May be null. */
    public String reason() {
        return reason;
    }

    /** Machine-readable parameters for a client-rendered message. Never contains prose. */
    public Map<String, String> details() {
        return details;
    }

    public static Builder of(ErrorCode code) {
        return new Builder(code, null);
    }

    public static Builder of(ErrorCode code, String reason) {
        return new Builder(code, reason);
    }

    public static final class Builder {
        private final ErrorCode code;
        private final String reason;
        private final Map<String, String> details = new LinkedHashMap<>();

        private Builder(ErrorCode code, String reason) {
            this.code = code;
            this.reason = reason;
        }

        public Builder with(String key, Object value) {
            details.put(key, String.valueOf(value));
            return this;
        }

        /** @param developerMessage English, for logs and developers. Never for end users. */
        public CoreException message(String developerMessage) {
            return new CoreException(code, reason, developerMessage, details);
        }
    }
}
