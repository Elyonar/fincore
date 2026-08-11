package org.elyonar.fincore.core.orchestration.internal.api;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.elyonar.fincore.auth.Authorization;
import org.elyonar.fincore.core.orchestration.api.CashCommand;
import org.elyonar.fincore.core.orchestration.api.TransferResult;
import org.elyonar.fincore.core.orchestration.internal.TenantZones;
import org.elyonar.fincore.core.orchestration.internal.saga.CashService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cash over the counter.
 *
 * <p>{@code CashService} and its tests have existed since Core's first slice; this is the wiring
 * that was missing, and its absence meant two endpoints the CHANGELOG listed as in-scope for v1
 * were reachable only from a test. Logic without a route is not a feature — it is a plan.
 *
 * <p>Deposit and withdrawal are separate endpoints rather than one with a direction field. The
 * direction of cash is the one thing that must never be defaulted, mistyped or flipped by a client
 * bug: a withdrawal recorded as a deposit balances perfectly and empties a till.
 */
@Tag(name = "Cash", description = "Deposits and withdrawals over the counter")
@RestController
@RequestMapping("/v1")
public class CashController {

    private final CashService cash;
    private final TenantZones zones;

    public CashController(CashService cash, TenantZones zones) {
        this.cash = cash;
        this.zones = zones;
    }

    /** Cash in: debits the till, credits the customer. */
    @PostMapping("/deposits")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResult deposit(@RequestBody CashRequest request) {
        return execute(CashCommand.Operation.DEPOSIT, request);
    }

    /** Cash out: debits the customer, credits the till. */
    @PostMapping("/withdrawals")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResult withdraw(@RequestBody CashRequest request) {
        return execute(CashCommand.Operation.WITHDRAWAL, request);
    }

    private TransferResult execute(CashCommand.Operation operation, CashRequest request) {
        var identity = Authorization.require("cash:transact");

        return cash.execute(
                new CashCommand(
                        // From the token, never the body.
                        identity.tenantId(),
                        operation,
                        request.idempotencyKey(),
                        request.fingerprint(operation),
                        request.customerId(),
                        request.customerAccountId(),
                        request.tillId(),
                        request.feeAccountId(),
                        request.amountMinor(),
                        request.currency(),
                        request.productCode(),
                        // Cash is counter business by definition; the channel is the endpoint,
                        // never a body field a caller could use to pick a limit tier (ADR 0012).
                        "TELLER",
                        request.description(),
                        Authorization.initiatedBy(),
                        identity.serviceIdentity() == null ? "core" : identity.serviceIdentity(),
                        zones.businessZone(identity.tenantId())));
    }

    /**
     * The request body.
     *
     * <p>The fingerprint covers economic content only, exactly as a transfer's does — so a
     * legitimate retry from another pod replays instead of colliding. The operation is part of it:
     * the same key used for a deposit and a withdrawal of the same amount is a caller bug, and it
     * must surface as one rather than replaying the wrong direction.
     */
    /**
     * @param productCode accepted and ignored, like {@code feeAccountId} before it. The product is
     *     read from the account the money moves through, because a caller able to name it could
     *     choose which fee and limit rules judged its own transaction. Still part of the
     *     idempotency fingerprint: a replay that changes it is a caller who has changed their mind
     *     about something, and answering 409 is better than pretending the two requests were one.
     */
    public record CashRequest(
            String idempotencyKey,
            UUID customerId,
            UUID customerAccountId,
            UUID tillId,
            UUID feeAccountId,
            long amountMinor,
            String currency,
            String productCode,
            String description) {

        String fingerprint(CashCommand.Operation operation) {
            return "%s|%s|%s|%s|%d|%s|%s"
                    .formatted(
                            operation, customerId, customerAccountId, tillId, amountMinor, currency, productCode);
        }
    }
}
