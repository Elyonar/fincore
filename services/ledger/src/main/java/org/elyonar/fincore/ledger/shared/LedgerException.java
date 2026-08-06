package org.elyonar.fincore.ledger.shared;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A rejection carrying a contract error code and the machine-readable facts behind it.
 *
 * <p>Rejections are total: the transaction rolls back, so no entries, no balance change and no
 * event survive it, and the idempotency key stays free. The registry binds committed operations
 * only ({@code docs/api.md}).
 *
 * <p><strong>The message is developer English and is never the thing a client shows a user.</strong>
 * A ledger cannot write end-user text: it does not know the channel, the locale, or whether the
 * reader is a teller in Lagos or a customer on USSD in Abidjan. What it can do is state precisely
 * <em>what</em> was wrong in a form a client can translate — the {@link #errorCode()}, a
 * {@link #reason()} where one code covers several distinct causes, and {@link #details()} carrying
 * the numbers a rendered message needs.
 *
 * <p>A French-speaking channel renders its own string from those. It never parses this message, and
 * this message never changes meaning without the code changing too.
 */
public class LedgerException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String reason;
    private final Map<String, String> details;

    public LedgerException(ErrorCode errorCode, String message) {
        this(errorCode, null, message, Map.of());
    }

    /**
     * @param reason distinguishes causes that share a code, e.g. {@code AMOUNT_NOT_INTEGER} versus
     *     {@code ENTRY_COUNT_EXCEEDED} under {@code LIMIT_EXCEEDED}. Null when the code alone is
     *     already unambiguous.
     * @param details the facts a translated message interpolates — a field name, a limit, what was
     *     actually supplied. Never prose.
     */
    public LedgerException(ErrorCode errorCode, String reason, String message, Map<String, String> details) {
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

    /** Builder for the common case of a code, a reason and a couple of parameters. */
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

        /** @param developerMessage English, for logs and developers. Not for end users. */
        public LedgerException message(String developerMessage) {
            return new LedgerException(code, reason, developerMessage, details);
        }
    }
}
