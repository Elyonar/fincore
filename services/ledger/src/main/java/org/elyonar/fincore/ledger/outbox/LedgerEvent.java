package org.elyonar.fincore.ledger.outbox;

/**
 * The six events this ledger emits. Deliberately a closed set.
 *
 * <p>Consumers react to money moving, so the vocabulary is part of the contract and cannot grow by
 * accident. Adding one is a design amendment, not an implementation detail.
 */
public enum LedgerEvent {
    ACCOUNT_CREATED("account.created"),
    ACCOUNT_CLOSED("account.closed"),
    POSTING_COMPLETED("posting.completed"),
    POSTING_REVERSED("posting.reversed"),
    HOLD_PLACED("hold.placed"),
    HOLD_RELEASED("hold.released");

    private final String wireName;

    LedgerEvent(String wireName) {
        this.wireName = wireName;
    }

    /** The name consumers match on; stable independently of the enum constant. */
    public String wireName() {
        return wireName;
    }
}
