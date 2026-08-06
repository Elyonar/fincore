package org.elyonar.fincore.core.customer.internal.api;

import java.util.UUID;
import org.elyonar.fincore.auth.Authorization;
import org.elyonar.fincore.core.customer.internal.CustomerRecords;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who the tenant's customers are.
 *
 * <p>Documented in {@code api.md} since v1.0 and unbuilt until now, which had a visible cost: every
 * test on this platform seeded {@code customer.customers} with raw SQL, because there was no other
 * way to create one. A schema that only tests can populate is not a module.
 *
 * <p>Note what is absent. There is no endpoint that deletes a customer and none that edits a tier
 * without a reason — a tier is the ceiling on what someone may move, so changing it silently is
 * changing a limit silently.
 */
@RestController
@RequestMapping("/v1/customers")
public class CustomerController {

    private final CustomerRecords customers;

    public CustomerController(CustomerRecords customers) {
        this.customers = customers;
    }

    /** Registers a customer. The tenant comes from the token, never the body. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerRecords.Profile create(@RequestBody CreateCustomer request) {
        var identity = Authorization.require("customers:create");
        return customers.create(
                identity.tenantId(),
                request.externalRef(),
                request.fullName(),
                request.phone(),
                // A tier the caller omits is the lowest one. Defaulting upward would hand out
                // limits by accident, and the safe direction here is obvious.
                request.kycTier() == null ? "TIER_1" : request.kycTier(),
                Authorization.initiatedBy());
    }

    /** The profile, tier, status and live account links. */
    @GetMapping("/{id}")
    public CustomerRecords.Profile read(@PathVariable UUID id) {
        var identity = Authorization.require("customers:read");
        CustomerRecords.Profile profile = customers.read(identity.tenantId(), id);
        if (profile == null) {
            throw new CustomerRecords.NoSuchCustomer();
        }
        return profile;
    }

    /**
     * Changes a KYC tier, attributed and with a reason.
     *
     * <p>The reason is required by the signature rather than encouraged by a comment. An audit
     * trail of tier changes with no reasons in it answers "what" and never "why", and "why" is the
     * question actually asked afterwards.
     */
    @PostMapping("/{id}/tier")
    public CustomerRecords.TierChange changeTier(@PathVariable UUID id, @RequestBody ChangeTier request) {
        var identity = Authorization.require("customers:tier");
        if (request.reason() == null || request.reason().isBlank()) {
            throw new IllegalArgumentException("REASON_REQUIRED");
        }
        return customers.changeTier(
                identity.tenantId(), id, request.toTier(), request.reason(), Authorization.initiatedBy());
    }

    /** Links a ledger account to this customer. */
    @PostMapping("/{id}/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerRecords.Link link(@PathVariable UUID id, @RequestBody LinkAccount request) {
        var identity = Authorization.require("customers:link");
        return customers.link(
                identity.tenantId(),
                id,
                request.ledgerAccountId(),
                request.currency(),
                request.role() == null ? "PRIMARY" : request.role());
    }

    /** @param externalRef the tenant's own customer number; unique within the tenant */
    public record CreateCustomer(String externalRef, String fullName, String phone, String kycTier) {}

    public record ChangeTier(String toTier, String reason) {}

    public record LinkAccount(UUID ledgerAccountId, String currency, String role) {}
}
