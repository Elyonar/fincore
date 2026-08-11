package org.elyonar.fincore.core.customer.internal.api;

import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.elyonar.fincore.core.customer.api.CustomerErrorCode;

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
@Tag(name = "Customers", description = "Who the tenant's customers are, their tiers and their accounts")
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
        // Checked here, as the neighbouring surfaces check theirs. Without it the request reached
        // the database and came back a 500 from a not-null constraint — the guard held, but the
        // caller was told nothing they could fix.
        if (request.fullName() == null || request.fullName().isBlank()) {
            throw new IllegalArgumentException(CustomerErrorCode.NAME_REQUIRED.code());
        }
        return customers.create(
                identity.tenantId(),
                request.externalRef(),
                request.fullName(),
                request.phone(),
                request.email(),
                request.locale(),
                // A tier the caller omits is the lowest one. Defaulting upward would hand out
                // limits by accident, and the safe direction here is obvious.
                request.kycTier() == null ? "TIER_1" : request.kycTier(),
                Authorization.initiatedBy());
    }

    /** The profile, tier, status and live account links. */
    /**
     * The search a teller screen opens with (ui-runway.md §3): name or reference, keyset-paged.
     * The cursor is opaque; a missing {@code q} lists from the top, which is how a small tenant
     * browses their whole book.
     */
    @GetMapping
    public java.util.Map<String, Object> search(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String q,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String page) {
        var identity = org.elyonar.fincore.auth.Authorization.require("customers:read");
        java.util.UUID after = Cursor.decode(page);
        var rows = customers.search(identity.tenantId(), q, after, 51);
        boolean more = rows.size() > 50;
        var pageRows = more ? rows.subList(0, 50) : rows;
        var view = new java.util.LinkedHashMap<String, Object>();
        view.put("customers", pageRows);
        view.put(
                "nextPage",
                more ? Cursor.encode((String) pageRows.get(pageRows.size() - 1).get("customerId")) : null);
        return view;
    }

    /** The cursor: an id, base64url-wrapped — opaque to callers, keyset underneath. */
    static final class Cursor {
        private Cursor() {}

        static String encode(String id) {
            return java.util.Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        static java.util.UUID decode(String page) {
            if (page == null || page.isBlank()) {
                return null;
            }
            try {
                return java.util.UUID.fromString(
                        new String(
                                java.util.Base64.getUrlDecoder().decode(page),
                                java.nio.charset.StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                return null; // an unreadable cursor restarts from the top rather than erroring
            }
        }
    }

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
            throw new IllegalArgumentException(CustomerErrorCode.REASON_REQUIRED.code());
        }
        return customers.changeTier(
                identity.tenantId(), id, request.toTier(), request.reason(), Authorization.initiatedBy());
    }

    /** Links a ledger account to this customer. */
    @PostMapping("/{id}/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerRecords.Link link(@PathVariable UUID id, @RequestBody LinkAccount request) {
        var identity = Authorization.require("customers:link");
        if (request.productCode() == null || request.productCode().isBlank()) {
            throw new IllegalArgumentException(CustomerErrorCode.PRODUCT_REQUIRED.code());
        }
        return customers.link(
                identity.tenantId(),
                id,
                request.ledgerAccountId(),
                request.currency(),
                request.role() == null ? "PRIMARY" : request.role(),
                request.productCode().trim());
    }

    /**
     * Who to contact about a ledger account, and what they agreed to.
     *
     * <p>The only lookup on this controller that runs from an account rather than a customer,
     * because it exists for services holding an account id and nothing else: a domain event carries
     * no PII (ADR 0008), so a sender must ask, on every send.
     *
     * <p>It carries its own permission rather than reusing {@code customers:read}. This returns
     * contact details and consent and nothing else — no name, no tier, no external reference — and
     * a machine that sends messages should be able to hold exactly that grant and no more.
     */
    @GetMapping("/by-account/{ledgerAccountId}")
    public CustomerRecords.ContactAndConsent contactForAccount(@PathVariable UUID ledgerAccountId) {
        var identity = Authorization.require("customers:contact");
        CustomerRecords.ContactAndConsent contact =
                customers.contactForAccount(identity.tenantId(), ledgerAccountId);
        if (contact == null) {
            throw new CustomerRecords.NoSuchCustomer();
        }
        return contact;
    }

    /**
     * Records what a customer agreed to, per category and channel.
     *
     * <p>Per category and channel rather than one flag, because "accepts transaction alerts by SMS,
     * refuses marketing, never asked about email" is one customer and three different answers. A
     * single flag collapses them, and the collapse always resolves in the direction that sends.
     */
    @PostMapping("/{id}/consent")
    public CustomerRecords.Consent recordConsent(@PathVariable UUID id, @RequestBody RecordConsent request) {
        var identity = Authorization.require("customers:consent");
        if (request.category() == null || request.channel() == null || request.granted() == null) {
            throw new IllegalArgumentException(CustomerErrorCode.CONSENT_INCOMPLETE.code());
        }
        return customers.recordConsent(
                identity.tenantId(),
                id,
                request.category(),
                request.channel(),
                request.granted(),
                Authorization.initiatedBy());
    }

    /** @param externalRef the tenant's own customer number; unique within the tenant */
    /**
     * @param locale BCP 47, and optional. Omitting it means nobody asked — the sending service
     *     falls back to the tenant default rather than this record claiming the customer chose
     *     English.
     */
    public record CreateCustomer(
            String externalRef, String fullName, String phone, String email, String locale, String kycTier) {}

    /**
     * @param granted boxed deliberately — an absent answer must be rejected, not silently read as
     *     "denied". A consent record that says a customer refused when nobody asked is a fabricated
     *     answer, and the compliance value of this table is that every row is a real one.
     */
    public record RecordConsent(String category, String channel, Boolean granted) {}

    public record ChangeTier(String toTier, String reason) {}

    /**
     * @param productCode what the account is held under. Required for the same reason it is
     *     required when opening one: the money path prices a transaction by the account's product,
     *     and an account without one cannot transact at all.
     */
    public record LinkAccount(UUID ledgerAccountId, String currency, String role, String productCode) {}
}
