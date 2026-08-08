package org.elyonar.fincore.core.orchestration.internal.api;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.elyonar.fincore.auth.Authorization;
import org.elyonar.fincore.core.orchestration.api.TransferCommand;
import org.elyonar.fincore.core.orchestration.api.TransferResult;
import org.elyonar.fincore.core.orchestration.internal.saga.ReversalService;
import org.elyonar.fincore.core.orchestration.internal.saga.SagaRecords;
import org.elyonar.fincore.core.orchestration.internal.saga.TransferService;
import org.elyonar.fincore.core.orchestration.api.CoreException;
import org.elyonar.fincore.core.orchestration.api.ErrorCode;
import org.elyonar.fincore.core.orchestration.api.ErrorReason;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The money-path endpoints. Every one denies by default and takes its tenant from the token. */
@Tag(name = "Transfers", description = "Book transfers and the non-mutating status read")
@RestController
@RequestMapping("/v1")
public class TransferController {

    /** The tenant's business timezone. Moves to tenant configuration once that exists. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Lagos");

    /** The channels v1 prices and limits by. A new channel is a design amendment, not a string. */
    private static final Set<String> CHANNELS = Set.of("API", "TELLER");

    private final TransferService transfers;
    private final ReversalService reversals;
    private final SagaRecords sagas;

    public TransferController(TransferService transfers, ReversalService reversals, SagaRecords sagas) {
        this.transfers = transfers;
        this.reversals = reversals;
        this.sagas = sagas;
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResult transfer(@RequestBody TransferRequest request) {
        var identity = Authorization.require("transfers:create");
        String channel = channel(request.channel());

        return transfers.transfer(
                new TransferCommand(
                        // From the token, never the body. A tenant a caller can assert is not a
                        // boundary — every downstream control would enforce the wrong one.
                        identity.tenantId(),
                        request.idempotencyKey(),
                        request.fingerprint(),
                        request.customerId(),
                        request.fromAccountId(),
                        request.toAccountId(),
                        request.feeAccountId(),
                        request.amountMinor(),
                        request.currency(),
                        request.productCode(),
                        channel,
                        request.description(),
                        Authorization.initiatedBy(),
                        identity.serviceIdentity() == null ? "core" : identity.serviceIdentity(),
                        BUSINESS_ZONE));
    }

    /**
     * What state a transaction is in — without changing it.
     *
     * <p>Exists so a crashed caller can ask what happened rather than mutating to find out.
     */
    @GetMapping("/transactions/{id}")
    public TransferResult status(@PathVariable UUID id) {
        var identity = Authorization.require("transfers:read");
        return sagas.read(identity.tenantId(), id);
    }

    /**
     * Reverses a completed transaction.
     *
     * <p>A <strong>business</strong> reversal: an operator undoing something that succeeded, which
     * is judgement, so it needs a second signature. Distinct from the saga's own compensation,
     * which undoes a step that definitely failed and needs no approval — mechanism, not judgement.
     * Conflating the two is how an approval requirement ends up on the recovery path, where it
     * would leave money reserved until a human woke up.
     *
     * <p>The approval is spent here and spendable once. Nothing about it is taken on the caller's
     * word: it is looked up by id and must be bound to this transaction and this amount.
     */
    @PostMapping("/transactions/{id}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResult reverse(@PathVariable UUID id, @RequestBody ReverseRequest request) {
        var identity = Authorization.require("transfers:reverse");
        return reversals.reverse(
                identity.tenantId(), id, request.approvalId(), request.idempotencyKey(), Authorization.initiatedBy());
    }

    /**
     * The channel, validated and permission-gated.
     *
     * <p>The channel selects which limit rules apply, which makes it an authorization input — and
     * an authorization input a caller can freely assert is a limit tier a caller can freely
     * choose. So asserting one costs a permission: {@code channel:api} to transact as an API
     * channel, {@code channel:teller} as a counter. Tokens carry channel permissions the same way
     * they carry everything else; a caller without the matching one is refused, 403.
     */
    private static String channel(String requested) {
        String channel = requested == null ? "API" : requested;
        if (!CHANNELS.contains(channel)) {
            throw new CoreException(
                    ErrorCode.COMMAND_INVALID,
                    ErrorReason.CHANNEL_INVALID,
                    "channel must be one of " + CHANNELS,
                    Map.of());
        }
        Authorization.require("channel:" + channel.toLowerCase(Locale.ROOT));
        return channel;
    }

    /** @param approvalId a maker-checker approval bound to this transaction and its amount */
    public record ReverseRequest(String idempotencyKey, UUID approvalId) {}

    /**
     * The request body.
     *
     * <p>The fingerprint covers economic content only — accounts, amount, currency, product.
     * Description and the initiating user are excluded, so a legitimate retry from a different pod
     * or session replays rather than 409s: two requests that move the same money identically are
     * the same request.
     */
    public record TransferRequest(
            String idempotencyKey,
            UUID customerId,
            UUID fromAccountId,
            UUID toAccountId,
            UUID feeAccountId,
            long amountMinor,
            String currency,
            String productCode,
            String channel,
            String description) {

        String fingerprint() {
            return "%s|%s|%s|%d|%s|%s"
                    .formatted(customerId, fromAccountId, toAccountId, amountMinor, currency, productCode);
        }
    }
}
