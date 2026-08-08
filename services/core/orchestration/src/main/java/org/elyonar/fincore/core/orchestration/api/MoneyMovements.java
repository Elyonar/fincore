package org.elyonar.fincore.core.orchestration.api;

import java.util.UUID;

/**
 * Orchestration's published surface for moving money — the boundary ADR 0013 lets Lending stand
 * on.
 *
 * <p>The same contract HTTP callers get, minus the network: every method is idempotent under its
 * command's key, refusals throw {@link CoreException} subtypes, and an unknown outcome throws the
 * caller into the retry rule rather than returning a guess. Consuming this interface — never the
 * internals behind it — is what keeps hard rule 3 ("only core-orchestration calls the Ledger")
 * true with a fifth module in the building.
 */
public interface MoneyMovements {

    /** A customer-channel transfer: eligibility, product decision, limits, fees — the full path. */
    TransferResult transfer(TransferCommand command);

    /** An institution-initiated funding movement: disbursement or repayment. See {@link FundingCommand}. */
    TransferResult fund(FundingCommand command);

    /** Saga state, non-mutating — how a caller converges on an outcome it missed. */
    TransferResult status(UUID tenantId, UUID transactionId);
}
