package org.elyonar.fincore.core.orchestration.internal.api;

import java.time.ZoneId;
import java.util.UUID;
import org.elyonar.fincore.auth.Authorization;
import org.elyonar.fincore.core.orchestration.api.TransferCommand;
import org.elyonar.fincore.core.orchestration.api.TransferResult;
import org.elyonar.fincore.core.orchestration.internal.saga.SagaRecords;
import org.elyonar.fincore.core.orchestration.internal.saga.TransferService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The money-path endpoints. Every one denies by default and takes its tenant from the token. */
@RestController
@RequestMapping("/v1")
public class TransferController {

    /** The tenant's business timezone. Comes from tenant configuration once that exists. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Lagos");

    private final TransferService transfers;
    private final SagaRecords sagas;

    public TransferController(TransferService transfers, SagaRecords sagas) {
        this.transfers = transfers;
        this.sagas = sagas;
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResult transfer(@RequestBody TransferRequest request) {
        var identity = Authorization.require("transfers:create");

        return transfers.transfer(
                new TransferCommand(
                        // Never from the body. A tenant a caller can assert is not a boundary.
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
                        request.channel() == null ? "API" : request.channel(),
                        request.description(),
                        Authorization.initiatedBy(),
                        Authorization.executedBy() == null ? "core" : Authorization.executedBy(),
                        BUSINESS_ZONE));
    }

    /**
     * What state a transaction is in — without changing it.
     *
     * <p>Exists so a crashed caller can ask what happened rather than having to mutate to find out.
     */
    @GetMapping("/transactions/{id}")
    public TransferResult status(@PathVariable UUID id) {
        var identity = Authorization.require("transfers:read");
        return sagas.read(identity.tenantId(), id);
    }

    /**
     * The request body.
     *
     * <p>The fingerprint covers economic content only, so a legitimate retry from a different pod
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
