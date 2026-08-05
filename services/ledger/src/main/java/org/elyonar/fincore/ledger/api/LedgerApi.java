package org.elyonar.fincore.ledger.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.ledger.account.AccountService;
import org.elyonar.fincore.ledger.account.OpenAccountCommand;
import org.elyonar.fincore.ledger.account.StatementService;
import org.elyonar.fincore.ledger.hold.HoldReleaseOutcome;
import org.elyonar.fincore.ledger.invariant.InvariantReport;
import org.elyonar.fincore.ledger.invariant.InvariantService;
import org.elyonar.fincore.ledger.hold.HoldService;
import org.elyonar.fincore.ledger.hold.HoldView;
import org.elyonar.fincore.ledger.hold.PlaceHoldCommand;
import org.elyonar.fincore.ledger.period.PeriodService;
import org.elyonar.fincore.ledger.posting.EntryLine;
import org.elyonar.fincore.ledger.posting.PostTransactionCommand;
import org.elyonar.fincore.ledger.posting.PostingResult;
import org.elyonar.fincore.ledger.posting.PostingService;
import org.elyonar.fincore.ledger.posting.ReversalService;
import org.elyonar.fincore.ledger.posting.ReverseTransactionCommand;
import org.elyonar.fincore.ledger.shared.ErrorCode;
import org.elyonar.fincore.ledger.shared.LedgerException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ledger's HTTP surface, as published in {@code docs/api.md}.
 *
 * <p>Every monetary field in a response is a decimal string; requests accept numbers or strings.
 * Writes are Orchestration-only — enforced at the transport by mTLS and the service-identity
 * allowlist rather than here, since a service that decides its own authorisation from a header is
 * not deciding anything.
 */
@RestController
@RequestMapping("/v1")
public class LedgerApi {

    private final AccountService accounts;
    private final StatementService statements;
    private final PostingService postings;
    private final ReversalService reversals;
    private final HoldService holds;
    private final PeriodService periods;
    private final InvariantService invariants;
    private final TenantResolver tenants;

    public LedgerApi(
            AccountService accounts,
            StatementService statements,
            PostingService postings,
            ReversalService reversals,
            HoldService holds,
            PeriodService periods,
            InvariantService invariants,
            TenantResolver tenants) {
        this.accounts = accounts;
        this.statements = statements;
        this.postings = postings;
        this.reversals = reversals;
        this.holds = holds;
        this.periods = periods;
        this.invariants = invariants;
        this.tenants = tenants;
    }

    // ---------------------------------------------------------------- accounts

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> openAccount(HttpServletRequest http, @RequestBody Map<String, Object> body) {
        UUID tenant = tenants.resolve(http);
        UUID id =
                accounts.open(
                        new OpenAccountCommand(
                                tenant,
                                required(body, "idempotencyKey"),
                                required(body, "type"),
                                required(body, "currency"),
                                text(body, "customerRef"),
                                text(body, "groupRef"),
                                Boolean.TRUE.equals(body.get("allowNegative"))));
        return Map.of("accountId", id.toString());
    }

    @GetMapping("/accounts/{id}")
    public Map<String, Object> getAccount(HttpServletRequest http, @PathVariable UUID id) {
        var account = accounts.find(tenants.resolve(http), id);
        return Map.of(
                "accountId", account.id().toString(),
                "type", account.type(),
                "currency", account.currency(),
                "status", account.status(),
                "allowNegative", account.allowNegative(),
                "currentMinor", Money.toWire(account.currentMinor()),
                "holdsTotalMinor", Money.toWire(account.holdsTotalMinor()),
                "availableMinor", Money.toWire(account.availableMinor()));
    }

    @PostMapping("/accounts/{id}/close")
    public Map<String, Object> closeAccount(
            HttpServletRequest http, @PathVariable UUID id, @RequestBody Map<String, Object> body) {
        accounts.close(tenants.resolve(http), id, required(body, "closedBy"));
        return Map.of("accountId", id.toString(), "status", "CLOSED");
    }

    @GetMapping("/accounts/{id}/entries")
    public Map<String, Object> statement(
            HttpServletRequest http,
            @PathVariable UUID id,
            @RequestParam String from,
            @RequestParam String to) {
        var statement =
                statements.forPeriod(tenants.resolve(http), id, LocalDate.parse(from), LocalDate.parse(to));
        return Map.of(
                "accountId", statement.accountId().toString(),
                "currency", statement.currency(),
                "from", statement.from().toString(),
                "to", statement.to().toString(),
                // Opening + movements = closing. The reconciliation is the document's own proof.
                "openingMinor", Money.toWire(statement.openingMinor()),
                "closingMinor", Money.toWire(statement.closingMinor()),
                // camt.053 vs camt.052: a closed period is final, an open one may still change.
                "status", statement.isFinal() ? "FINAL" : "INTERIM",
                "lines",
                        statement.lines().stream()
                                .map(
                                        l ->
                                                Map.<String, Object>of(
                                                        "entryId", Long.toString(l.entryId()),
                                                        "transactionId", l.transactionId().toString(),
                                                        "direction", l.direction(),
                                                        "amountMinor", Money.toWire(l.amountMinor()),
                                                        "currency", l.currency(),
                                                        "valueDate", l.valueDate().toString(),
                                                        "bookedAt", l.bookedAt().toString()))
                                .toList());
    }

    @GetMapping("/accounts/{id}/holds")
    public List<Map<String, Object>> accountHolds(
            HttpServletRequest http, @PathVariable UUID id, @RequestParam(required = false) String status) {
        return accounts.holdsOn(tenants.resolve(http), id, status).stream()
                .map(
                        h ->
                                Map.<String, Object>of(
                                        "holdId", h.id().toString(),
                                        "amountMinor", Money.toWire(h.amountMinor()),
                                        "currency", h.currency(),
                                        "status", h.status(),
                                        "expiresAt", h.expiresAt().toString()))
                .toList();
    }

    @GetMapping("/account-groups/{groupRef}/balance")
    public Map<String, Object> groupBalance(HttpServletRequest http, @PathVariable String groupRef) {
        var group = accounts.groupBalance(tenants.resolve(http), groupRef);
        return Map.of(
                "groupRef", group.groupRef(),
                "memberCount", group.memberCount(),
                // The field most likely to exceed 2^53 in practice, since it sums across shards.
                "currentMinor", Money.toWire(group.currentMinor()),
                "holdsTotalMinor", Money.toWire(group.holdsTotalMinor()));
    }

    // ------------------------------------------------------------ transactions

    @PostMapping("/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    @SuppressWarnings("unchecked")
    public Map<String, Object> post(HttpServletRequest http, @RequestBody Map<String, Object> body) {
        UUID tenant = tenants.resolve(http);
        List<Map<String, Object>> rawEntries = (List<Map<String, Object>>) body.get("entries");
        if (rawEntries == null || rawEntries.isEmpty()) {
            throw new LedgerException(ErrorCode.UNBALANCED, "entries are required");
        }

        List<EntryLine> entries =
                rawEntries.stream()
                        .map(
                                e ->
                                        new EntryLine(
                                                UUID.fromString(required(e, "accountId")),
                                                EntryLine.Direction.valueOf(required(e, "direction")),
                                                Money.fromWire(e.get("amountMinor"), "amountMinor"),
                                                required(e, "currency"),
                                                date(e.get("valueDate"))))
                        .toList();

        PostingResult result =
                postings.post(
                        new PostTransactionCommand(
                                tenant,
                                required(body, "idempotencyKey"),
                                required(body, "initiatedBy"),
                                text(body, "executedBy") == null ? "svc:orchestration" : text(body, "executedBy"),
                                text(body, "description"),
                                entries,
                                uuid(body.get("consumeHoldId")),
                                uuid(body.get("relatesToTransactionId")),
                                text(body, "backdateReason"),
                                Boolean.TRUE.equals(body.get("closedAccountSweep"))));

        return Map.of("transactionId", result.transactionId().toString(), "replayed", result.replayed());
    }

    @PostMapping("/transactions/{id}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> reverse(
            HttpServletRequest http, @PathVariable UUID id, @RequestBody Map<String, Object> body) {
        PostingResult result =
                reversals.reverse(
                        new ReverseTransactionCommand(
                                tenants.resolve(http),
                                id,
                                required(body, "idempotencyKey"),
                                required(body, "initiatedBy"),
                                text(body, "executedBy") == null ? "svc:orchestration" : text(body, "executedBy")));
        return Map.of(
                "reversalTransactionId", result.transactionId().toString(),
                "originalTransactionId", id.toString(),
                "replayed", result.replayed());
    }

    // ------------------------------------------------------------------- holds

    @PostMapping("/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> placeHold(HttpServletRequest http, @RequestBody Map<String, Object> body) {
        UUID id =
                holds.place(
                        new PlaceHoldCommand(
                                tenants.resolve(http),
                                required(body, "idempotencyKey"),
                                UUID.fromString(required(body, "accountId")),
                                Money.fromWire(body.get("amountMinor"), "amountMinor"),
                                required(body, "currency"),
                                Instant.parse(required(body, "expiresAt"))));
        return Map.of("holdId", id.toString());
    }

    @GetMapping("/holds/{id}")
    public Map<String, Object> getHold(HttpServletRequest http, @PathVariable UUID id) {
        HoldView hold = holds.find(tenants.resolve(http), id);
        if (hold == null) {
            throw new LedgerException(ErrorCode.ACCOUNT_NOT_FOUND, "unknown hold " + id);
        }
        return Map.of(
                "holdId", hold.id().toString(),
                "accountId", hold.accountId().toString(),
                "amountMinor", Money.toWire(hold.amountMinor()),
                "currency", hold.currency(),
                "status", hold.status(),
                "expiresAt", hold.expiresAt().toString());
    }

    @PostMapping("/holds/{id}/release")
    public Map<String, Object> releaseHold(HttpServletRequest http, @PathVariable UUID id) {
        HoldReleaseOutcome outcome = holds.release(tenants.resolve(http), id);
        // The transition itself, never a bare success: a caller whose reservation expired must
        // learn that rather than believe it recovered funds.
        return Map.of("holdId", id.toString(), "outcome", outcome.name());
    }

    // ----------------------------------------------------------------- periods

    @GetMapping("/periods")
    public List<Map<String, Object>> listPeriods(HttpServletRequest http) {
        return periods.list(tenants.resolve(http)).stream()
                .map(
                        p ->
                                Map.<String, Object>of(
                                        "periodEnd", p.periodEnd().toString(),
                                        "closedAt", p.closedAt().toString(),
                                        "closedBy", p.closedBy()))
                .toList();
    }

    @PostMapping("/periods/{end}/close")
    public Map<String, Object> closePeriod(
            HttpServletRequest http, @PathVariable("end") String end, @RequestBody Map<String, Object> body) {
        LocalDate periodEnd = LocalDate.parse(end);
        periods.close(tenants.resolve(http), periodEnd, required(body, "closedBy"));
        return Map.of("periodEnd", periodEnd.toString(), "status", "CLOSED");
    }

    // -------------------------------------------------------------- invariants

    @GetMapping("/invariants")
    public Map<String, Object> latestInvariantReport(HttpServletRequest http) {
        // Fetches only. An endpoint that could trigger a full-history scan on demand would be a
        // denial-of-service lever pointed at the ledger's own database.
        InvariantReport report = invariants.latest(tenants.resolve(http));
        if (report == null) {
            return Map.of("status", "NO_RUN_YET");
        }
        return Map.of(
                "runId", Long.toString(report.runId()),
                "startedAt", report.startedAt().toString(),
                "completedAt", report.completedAt() == null ? "" : report.completedAt().toString(),
                "scope", report.scope(),
                "status", report.clean() ? "CLEAN" : "VIOLATIONS");
    }

    @PostMapping("/invariants/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> runInvariants(HttpServletRequest http) {
        InvariantReport report = invariants.verify(tenants.resolve(http));
        return Map.of(
                "runId", Long.toString(report.runId()),
                "violations", report.violations(),
                "exposures", report.exposures(),
                "status", report.clean() ? "CLEAN" : "VIOLATIONS",
                "findings",
                        report.findings().stream()
                                .map(
                                        f ->
                                                Map.<String, Object>of(
                                                        "kind", f.kind().name(),
                                                        "invariant", f.invariant(),
                                                        "subject", f.subject(),
                                                        "detail", f.detail()))
                                .toList());
    }

    // ------------------------------------------------------------------ helpers

    private static String required(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return String.valueOf(value);
    }

    private static String text(Map<String, Object> body, String field) {
        Object value = body.get(field);
        return value == null ? null : String.valueOf(value);
    }

    private static UUID uuid(Object raw) {
        return raw == null ? null : UUID.fromString(String.valueOf(raw));
    }

    private static LocalDate date(Object raw) {
        return raw == null ? null : LocalDate.parse(String.valueOf(raw));
    }
}
