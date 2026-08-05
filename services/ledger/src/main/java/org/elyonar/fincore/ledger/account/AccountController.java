package org.elyonar.fincore.ledger.account;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.ledger.api.Money;
import org.elyonar.fincore.ledger.api.TenantHeader;
import org.elyonar.fincore.ledger.api.TenantResolver;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Accounts, their balances, their holds, and their statements. */
@RestController
@RequestMapping("/v1")
@Tag(name = "Accounts", description = "Opening, reading and closing accounts")
@TenantHeader
public class AccountController {

    private final AccountService accounts;
    private final StatementService statements;
    private final TenantResolver tenants;

    public AccountController(AccountService accounts, StatementService statements, TenantResolver tenants) {
        this.accounts = accounts;
        this.statements = statements;
        this.tenants = tenants;
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Open an account",
            description =
                    "Idempotent on the caller's key: a retry returns the original account rather than"
                        + " orphaning a duplicate that would then quietly receive half the traffic. The"
                        + " balance row is created in the same transaction, so a posting can never find"
                        + " an account without the row it must lock.")
    public OpenAccountResponse open(HttpServletRequest http, @RequestBody OpenAccountRequest request) {
        UUID id =
                accounts.open(
                        new OpenAccountCommand(
                                tenants.resolve(http),
                                request.idempotencyKey(),
                                request.type(),
                                request.currency(),
                                request.customerRef(),
                                request.groupRef(),
                                Boolean.TRUE.equals(request.allowNegative())));
        return new OpenAccountResponse(id.toString());
    }

    @GetMapping("/accounts/{id}")
    @Operation(
            summary = "Read an account and its balance",
            description = "Another tenant's account is 404, deliberately indistinguishable from unknown.")
    public AccountResponse get(HttpServletRequest http, @PathVariable UUID id) {
        var a = accounts.find(tenants.resolve(http), id);
        return new AccountResponse(
                a.id().toString(),
                a.type(),
                a.currency(),
                a.status(),
                a.allowNegative(),
                Money.toWire(a.currentMinor()),
                Money.toWire(a.holdsTotalMinor()),
                Money.toWire(a.availableMinor()));
    }

    @PostMapping("/accounts/{id}/close")
    @Operation(
            summary = "Close an account",
            description =
                    "Requires a zero balance and no active holds, under the balance row lock. There is no"
                        + " reopen — a reopenable account would make 'closed' mean nothing.")
    public CloseAccountResponse close(
            HttpServletRequest http, @PathVariable UUID id, @RequestBody CloseAccountRequest request) {
        accounts.close(tenants.resolve(http), id, request.closedBy());
        return new CloseAccountResponse(id.toString(), "CLOSED");
    }

    @GetMapping("/accounts/{id}/entries")
    @Operation(
            summary = "Statement for a period",
            description =
                    "A bounded document, not a change feed. Opening + movements = closing, and that"
                        + " reconciliation is the statement's own proof of integrity. A closed accounting"
                        + " period is FINAL and will never change; an open one is INTERIM. Do not hold a"
                        + " cursor over this endpoint — entry ids are assigned at insert rather than"
                        + " commit, so a cursor silently skips late-committing entries. The outbox is the"
                        + " change feed.")
    public StatementResponse statement(
            HttpServletRequest http,
            @PathVariable UUID id,
            @RequestParam @Schema(example = "2026-08-01") String from,
            @RequestParam @Schema(example = "2026-08-31") String to,
            @RequestParam(required = false, defaultValue = "500")
                    @Schema(description = "Lines per page; capped at 1000", example = "500")
                    Integer limit,
            @RequestParam(required = false)
                    @Schema(description = "From a previous page's nextCursor. Walks this period only.")
                    String after) {
        var s =
                statements.forPeriod(
                        tenants.resolve(http),
                        id,
                        LocalDate.parse(from),
                        LocalDate.parse(to),
                        limit == null ? StatementService.DEFAULT_PAGE_SIZE : limit,
                        after);
        return new StatementResponse(
                s.accountId().toString(),
                s.currency(),
                s.from().toString(),
                s.to().toString(),
                Money.toWire(s.openingMinor()),
                Money.toWire(s.closingMinor()),
                s.isFinal() ? "FINAL" : "INTERIM",
                s.nextCursor(),
                s.lines().stream()
                        .map(
                                l ->
                                        new StatementLine(
                                                Long.toString(l.entryId()),
                                                l.transactionId().toString(),
                                                l.direction(),
                                                Money.toWire(l.amountMinor()),
                                                l.currency(),
                                                l.valueDate().toString(),
                                                l.bookedAt().toString()))
                        .toList());
    }

    @GetMapping("/accounts/{id}/holds")
    @Operation(summary = "Holds on an account")
    public List<HoldSummaryResponse> holds(
            HttpServletRequest http,
            @PathVariable UUID id,
            @RequestParam(required = false) @Schema(allowableValues = {"ACTIVE", "RELEASED", "EXPIRED", "CONSUMED"})
                    String status) {
        return accounts.holdsOn(tenants.resolve(http), id, status).stream()
                .map(
                        h ->
                                new HoldSummaryResponse(
                                        h.id().toString(),
                                        Money.toWire(h.amountMinor()),
                                        h.currency(),
                                        h.status(),
                                        h.expiresAt().toString()))
                .toList();
    }

    @GetMapping("/account-groups/{groupRef}/balance")
    @Operation(
            summary = "Summed balance across a fan-in shard group",
            description =
                    "Hot internal accounts are sharded so writers do not queue on one row. This sum is"
                        + " the field most likely to exceed 2^53, which is why every monetary value here"
                        + " is a string.")
    public GroupBalanceResponse groupBalance(HttpServletRequest http, @PathVariable String groupRef) {
        var g = accounts.groupBalance(tenants.resolve(http), groupRef);
        return new GroupBalanceResponse(
                g.groupRef(), g.memberCount(), Money.toWire(g.currentMinor()), Money.toWire(g.holdsTotalMinor()));
    }

    // ------------------------------------------------------------------- DTOs

    @Schema(description = "Request to open an account")
    public record OpenAccountRequest(
            @Schema(example = "orch-acct-000124", description = "Unique per tenant; a retry returns the original")
                    String idempotencyKey,
            @Schema(
                            example = "CUSTOMER",
                            allowableValues = {
                                "CUSTOMER", "INTERNAL", "FEE", "SUSPENSE", "AGENT_FLOAT", "SETTLEMENT_MIRROR"
                            })
                    String type,
            @Schema(example = "NGN", description = "ISO 4217; must exist in the currencies table") String currency,
            @Schema(description = "Opaque reference. The ledger stores no PII.") String customerRef,
            @Schema(description = "Optional fan-in group, for sharding a hot internal account") String groupRef,
            @Schema(
                            description =
                                    "May the balance go below zero? True for settlement mirrors and fee"
                                        + " accounts; false for customer money.")
                    Boolean allowNegative) {}

    public record OpenAccountResponse(String accountId) {}

    public record CloseAccountRequest(
            @Schema(example = "user:ops.ada", description = "Attribution is required") String closedBy) {}

    public record CloseAccountResponse(String accountId, String status) {}

    @Schema(description = "An account and its balance. All monetary fields are decimal strings.")
    public record AccountResponse(
            String accountId,
            String type,
            String currency,
            String status,
            boolean allowNegative,
            @Schema(example = "50000") String currentMinor,
            @Schema(example = "0") String holdsTotalMinor,
            @Schema(example = "50000", description = "current − holds") String availableMinor) {}

    public record GroupBalanceResponse(
            String groupRef, int memberCount, String currentMinor, String holdsTotalMinor) {}

    public record HoldSummaryResponse(
            String holdId, String amountMinor, String currency, String status, String expiresAt) {}

    @Schema(description = "A period statement. opening + movements = closing.")
    public record StatementResponse(
            String accountId,
            String currency,
            String from,
            String to,
            String openingMinor,
            String closingMinor,
            @Schema(allowableValues = {"FINAL", "INTERIM"}) String status,
            @Schema(
                            description =
                                    "Pass as `after` for the next page. Null when this is the last page."
                                        + " opening and closing describe the whole period on every page.")
                    String nextCursor,
            List<StatementLine> lines) {}

    public record StatementLine(
            String entryId,
            String transactionId,
            String direction,
            String amountMinor,
            String currency,
            @Schema(description = "When it counts") String valueDate,
            @Schema(description = "When the ledger recorded it") String bookedAt) {}
}
