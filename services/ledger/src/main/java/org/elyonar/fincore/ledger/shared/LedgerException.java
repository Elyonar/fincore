package org.elyonar.fincore.ledger.shared;

/**
 * A rejection carrying a contract error code.
 *
 * <p>Rejections are total: the transaction rolls back, so no entries, no balance change and no
 * event survive it, and the idempotency key stays free. The registry binds committed operations
 * only ({@code docs/api.md}).
 */
public class LedgerException extends RuntimeException {

    private final ErrorCode errorCode;

    public LedgerException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
