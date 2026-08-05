package org.elyonar.fincore.ledger.posting;

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
import org.elyonar.fincore.ledger.shared.ErrorCode;
import org.elyonar.fincore.ledger.shared.LedgerException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Posting and reversing money movements. Orchestration only. */
@RestController
@RequestMapping("/v1")
@Tag(name = "Transactions", description = "Posting and reversing money movements")
@TenantHeader
public class TransactionController {

    private final PostingService postings;
    private final ReversalService reversals;
    private final TransactionReadService transactions;
    private final TenantResolver tenants;

    public TransactionController(
            PostingService postings,
            ReversalService reversals,
            TransactionReadService transactions,
            TenantResolver tenants) {
        this.postings = postings;
        this.reversals = reversals;
        this.transactions = transactions;
        this.tenants = tenants;
    }

    @GetMapping("/transactions/{id}")
    @Operation(
            summary = "Read a transaction and its entries",
            description =
                    "How a caller confirms what actually posted — for reconciliation, for showing a"
                        + " teller the result, and for settling a dispute against the record rather than"
                        + " against someone's memory of it. Another tenant's transaction is a 404,"
                        + " indistinguishable from one that does not exist.")
    public TransactionResponse get(HttpServletRequest http, @PathVariable UUID id) {
        var t = transactions.find(tenants.resolve(http), id);
        return new TransactionResponse(
                t.id().toString(),
                t.idempotencyKey(),
                t.status(),
                t.initiatedBy(),
                t.executedBy(),
                t.reversesTransactionId() == null ? null : t.reversesTransactionId().toString(),
                t.relatesToTransactionId() == null ? null : t.relatesToTransactionId().toString(),
                t.backdateReason(),
                t.postedAt().toString(),
                t.entries().stream()
                        .map(
                                e ->
                                        new EntryResponse(
                                                Long.toString(e.entryId()),
                                                e.accountId().toString(),
                                                e.direction(),
                                                Money.toWire(e.amountMinor()),
                                                e.currency(),
                                                e.valueDate().toString(),
                                                e.bookedAt().toString()))
                        .toList());
    }

    @PostMapping("/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Post a balanced transaction",
            description =
                    "Debits must equal credits **within each currency**. Committed in one database"
                        + " transaction: complete or absent, never partial. A rejection leaves no rows and"
                        + " no event, so the idempotency key stays free for a genuine retry.\n\n"
                        + "Replaying the same key with the same payload returns the original result"
                        + " (`replayed: true`). The same key with a *different* payload is a 409 —"
                        + " never a silent wrong answer.")
    public PostTransactionResponse post(HttpServletRequest http, @RequestBody PostTransactionRequest request) {
        if (request.entries() == null || request.entries().isEmpty()) {
            throw new LedgerException(ErrorCode.UNBALANCED, "entries are required");
        }

        List<EntryLine> entries =
                request.entries().stream()
                        .map(
                                e ->
                                        new EntryLine(
                                                UUID.fromString(e.accountId()),
                                                EntryLine.Direction.valueOf(e.direction()),
                                                Money.fromWire(e.amountMinor(), "amountMinor"),
                                                e.currency(),
                                                e.valueDate() == null ? null : LocalDate.parse(e.valueDate())))
                        .toList();

        PostingResult result =
                postings.post(
                        new PostTransactionCommand(
                                tenants.resolve(http),
                                request.idempotencyKey(),
                                request.initiatedBy(),
                                request.executedBy() == null ? "svc:orchestration" : request.executedBy(),
                                request.description(),
                                entries,
                                request.consumeHoldId() == null ? null : UUID.fromString(request.consumeHoldId()),
                                request.relatesToTransactionId() == null
                                        ? null
                                        : UUID.fromString(request.relatesToTransactionId()),
                                request.backdateReason(),
                                Boolean.TRUE.equals(request.closedAccountSweep())));

        return new PostTransactionResponse(result.transactionId().toString(), result.replayed());
    }

    @PostMapping("/transactions/{id}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Reverse a posted transaction",
            description =
                    "Undo by mirroring, never by editing: the original entries stay exactly as written and"
                        + " an opposing transaction is recorded. Mirrored entries carry *today's* value"
                        + " date, since posting into the past would rewrite a period that may be closed."
                        + " Reversal deliberately bypasses the negative-balance guard and the"
                        + " closed-account check, because undoing must always be possible.\n\n"
                        + "Exactly once. A second attempt is a 409 carrying the winning reversal's id in"
                        + " `detail`, so a saga converges instead of retry-looping.")
    public ReverseResponse reverse(
            HttpServletRequest http, @PathVariable UUID id, @RequestBody ReverseRequest request) {
        PostingResult result =
                reversals.reverse(
                        new ReverseTransactionCommand(
                                tenants.resolve(http),
                                id,
                                request.idempotencyKey(),
                                request.initiatedBy(),
                                request.executedBy() == null ? "svc:orchestration" : request.executedBy()));
        return new ReverseResponse(result.transactionId().toString(), id.toString(), result.replayed());
    }

    // ------------------------------------------------------------------- DTOs

    @Schema(description = "A balanced transaction. Debits must equal credits within each currency.")
    public record PostTransactionRequest(
            @Schema(example = "orch-7f3a-2026-08-05-000124", description = "Unique per tenant")
                    String idempotencyKey,
            @Schema(example = "user:ada.o@branch-01", description = "The human or system that asked")
                    String initiatedBy,
            @Schema(example = "svc:orchestration", description = "The service that executed") String executedBy,
            @Schema(example = "NIBSS outbound transfer") String description,
            List<EntryRequest> entries,
            @Schema(description = "Capture a hold atomically with this posting") String consumeHoldId,
            @Schema(description = "Mark this as a compensation for an earlier transaction")
                    String relatesToTransactionId,
            @Schema(description = "Required when any value date is in the past") String backdateReason,
            @Schema(
                            description =
                                    "Clear reversal residue from a closed account. Must bring it to exactly"
                                        + " zero, with a SUSPENSE counterparty.")
                    Boolean closedAccountSweep) {}

    @Schema(description = "One side of a posting")
    public record EntryRequest(
            @Schema(format = "uuid") String accountId,
            @Schema(allowableValues = {"DEBIT", "CREDIT"}) String direction,
            @Schema(
                            example = "50000",
                            description =
                                    "Integer minor units (kobo). A number or a string; a decimal is refused"
                                        + " rather than rounded.")
                    Object amountMinor,
            @Schema(example = "NGN") String currency,
            @Schema(example = "2026-08-05", description = "Defaults to the tenant's business date")
                    String valueDate) {}

    public record PostTransactionResponse(
            String transactionId,
            @Schema(description = "True when this call returned an earlier call's result") boolean replayed) {}

    public record ReverseRequest(
            @Schema(description = "The reversal's own key, not the original's") String idempotencyKey,
            String initiatedBy,
            String executedBy) {}

    public record ReverseResponse(
            String reversalTransactionId, String originalTransactionId, boolean replayed) {}

    @Schema(description = "A transaction and the entries it wrote")
    public record TransactionResponse(
            String transactionId,
            String idempotencyKey,
            @Schema(allowableValues = {"POSTED", "REVERSED"}) String status,
            String initiatedBy,
            String executedBy,
            @Schema(description = "Set when this transaction is itself a reversal")
                    String reversesTransactionId,
            @Schema(description = "Set when this transaction compensates another")
                    String relatesToTransactionId,
            String backdateReason,
            String postedAt,
            List<EntryResponse> entries) {}

    public record EntryResponse(
            String entryId,
            String accountId,
            String direction,
            String amountMinor,
            String currency,
            @Schema(description = "When it counts") String valueDate,
            @Schema(description = "When the ledger recorded it") String bookedAt) {}
}
