package org.elyonar.fincore.core.orchestration.api;

import java.util.UUID;

/**
 * An institution-initiated movement: a loan disbursement out of the tenant's funding account, or
 * a repayment into it (lending.md, ADR 0013).
 *
 * <p>Not a {@link TransferCommand}, deliberately: no channel (this is not channel traffic), no
 * fee (pricing was the loan's), no product evaluation and no limit reservation (the exposure was
 * approved by Lending's chain; channel limits protect customer-initiated movement). What it keeps
 * is everything that makes a saga a saga: the idempotency key, the fingerprint, the three-valued
 * outcome, the worker, the ops case.
 *
 * @param customerId required for {@code REPAYMENT} (the money leaves a customer's account, so
 *     eligibility and account-holding are checked); null for {@code DISBURSEMENT}, whose source
 *     is the tenant's own funding account
 * @param reference the business fact this movement serves, e.g. the loan id — recorded as the
 *     posting description so the ledger's record answers "why" without a join
 */
public record FundingCommand(
        UUID tenantId,
        Kind kind,
        String idempotencyKey,
        UUID customerId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        long amountMinor,
        String currency,
        String reference,
        String initiatedBy,
        String executedBy) {

    public enum Kind {
        DISBURSEMENT,
        REPAYMENT
    }

    public FundingCommand {
        if (amountMinor <= 0) {
            throw CoreException.of(ErrorCode.AMOUNT_INVALID, ErrorReason.AMOUNT_NOT_POSITIVE)
                    .with(DetailKey.FIELD, "amountMinor")
                    .message("amountMinor must be positive");
        }
        if (kind == Kind.REPAYMENT && customerId == null) {
            throw CoreException.of(ErrorCode.COMMAND_INVALID, ErrorReason.FIELD_REQUIRED)
                    .with(DetailKey.FIELD, "customerId")
                    .message("a repayment names the customer it collects from");
        }
    }

    /** Economic content only, like every fingerprint here. */
    public String fingerprint() {
        return "%s|%s|%s|%s|%d|%s"
                .formatted(kind, customerId, sourceAccountId, destinationAccountId, amountMinor, currency);
    }
}
