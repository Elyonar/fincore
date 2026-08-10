package org.elyonar.fincore.core.orchestration.internal.api;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.elyonar.fincore.auth.Authorization;
import org.elyonar.fincore.core.orchestration.api.CoreException;
import org.elyonar.fincore.core.orchestration.api.DetailKey;
import org.elyonar.fincore.core.orchestration.api.ErrorCode;
import org.elyonar.fincore.core.orchestration.internal.saga.InternalAccounts;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The institution's own accounts (admin-surface §4).
 *
 * <p>This closes the gap that made every other configuration screen unusable. Fee rules point at a
 * fee income account, loan rules point at funding and interest accounts, a till is a ledger
 * account — and until now no account could be created through the platform at all. The ledger's
 * write API is not routed by the edge and never will be (ADR 0014); {@code LedgerClient} had no
 * way to open one; nothing seeded them. An institution could be provisioned, staffed, and unable
 * to take a deposit.
 *
 * <p>Deliberately not a passthrough. The ledger is the authority on the account and the money in
 * it; Core is the authority on what the institution calls it and what it is for, because a chart
 * of accounts made of bare UUIDs is one no operator can use and no report can label.
 */
@Tag(name = "Internal accounts", description = "The institution's own ledger accounts")
@RestController
@RequestMapping("/v1")
public class InternalAccountController {

    private final InternalAccounts accounts;

    public InternalAccountController(InternalAccounts accounts) {
        this.accounts = accounts;
    }

    /** The institution's chart of accounts. */
    @GetMapping("/internal-accounts")
    public List<InternalAccounts.InternalAccount> list() {
        var identity = Authorization.require("accounts:read");
        return accounts.list(identity.tenantId());
    }

    /**
     * Opens one, in the ledger and in the register, and returns what it is called.
     *
     * <p>Not maker-checked, and that is a judgement rather than an omission: an account with no
     * postings holds nothing, and the operations that put money into it — a deposit, a fee, a
     * disbursement — carry their own controls. What would deserve a second signature is pointing a
     * *fee rule* at an account, and that lives on the product surface.
     */
    @PostMapping("/internal-accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public InternalAccounts.InternalAccount open(@RequestBody OpenInternalAccount request) {
        var identity = Authorization.require("accounts:manage");

        InternalAccounts.Purpose purpose;
        try {
            purpose = InternalAccounts.Purpose.valueOf(
                    String.valueOf(request.purpose()).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new CoreException(
                    ErrorCode.COMMAND_INVALID,
                    null,
                    "purpose must be one of " + java.util.Arrays.toString(InternalAccounts.Purpose.values()),
                    Map.of(DetailKey.FIELD, "purpose"));
        }

        return accounts.open(
                identity.tenantId(),
                request.code(),
                request.name(),
                purpose,
                request.currency(),
                Authorization.initiatedBy());
    }

    /**
     * @param code the institution's own short reference, e.g. {@code fee-income-ngn}. Permanent,
     *     and how every other screen names this account instead of showing a UUID.
     * @param purpose TILL, VAULT, FEE_INCOME, INTEREST_INCOME, PENALTY_INCOME, LOAN_FUNDING,
     *     SUSPENSE, SETTLEMENT or OTHER. Decides which ledger account type is opened.
     */
    public record OpenInternalAccount(String code, String name, String purpose, String currency) {}
}
