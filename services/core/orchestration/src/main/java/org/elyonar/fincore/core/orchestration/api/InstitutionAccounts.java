package org.elyonar.fincore.core.orchestration.api;

import java.util.List;
import java.util.UUID;

/**
 * The institution's own accounts, as a neighbour may ask about them.
 *
 * <p>Published because pricing points at them. A fee rule names the account a fee credits, a loan
 * rule names where interest is recognised and where a disbursement is funded — and until this port
 * existed the only way to check that such a UUID was a real account of the right kind was not to
 * check. That is exactly the hole {@code V4__fee_account_configuration.sql} was written to close
 * and could not, because the module that owns pricing may not reach into the module that owns the
 * ledger client (ADR 0006, hard rule 3).
 *
 * <p>Read-only on purpose. Opening an account is a call to another service with a failure mode
 * worth handling deliberately; a neighbour that could trigger one as a side effect of saving a fee
 * rule would be opening accounts nobody asked for.
 */
public interface InstitutionAccounts {

    /**
     * @param purpose the bank's own vocabulary — TILL, FEE_INCOME, LOAN_FUNDING and so on — rather
     *     than the ledger's posting type, because that is the question a caller is actually asking
     */
    record Account(
            UUID id,
            UUID ledgerAccountId,
            String code,
            String name,
            String purpose,
            String currency,
            String status) {

        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    /** Every account this institution has opened. */
    List<Account> all(UUID tenantId);

    /** The account behind a ledger account id, or null when this tenant has not opened one. */
    Account byLedgerAccountId(UUID tenantId, UUID ledgerAccountId);
}
