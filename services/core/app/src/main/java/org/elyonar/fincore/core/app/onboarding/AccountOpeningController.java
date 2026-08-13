package org.elyonar.fincore.core.app.onboarding;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.auth.Authorization;
import org.elyonar.fincore.core.orchestration.api.CustomerAdministration;
import org.elyonar.fincore.core.orchestration.api.InstitutionAccounts;
import org.elyonar.fincore.core.orchestration.api.ProductCatalogue;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Opening a customer's account, and the numbering it comes from (admin-surface §4).
 *
 * <p>Before this, {@code POST /v1/customers/{id}/accounts} <em>linked</em> a ledger account UUID
 * the caller had to already possess — and nothing could produce one. A customer could be created
 * and could not hold an account, which is the whole of retail banking.
 *
 * <p>Two modules and one act: Orchestration opens the account because it is the only module that
 * may address the ledger, Customer records who holds it and under what number, and this composes
 * them. In that order deliberately — an account that exists and is unlinked is a reconciliation
 * item somebody can find, while a link to an account that does not exist is a number on a
 * statement that resolves to nothing.
 */
@Tag(name = "Account opening", description = "Opening customer accounts, and how they are numbered")
@RestController
@RequestMapping("/v1")
public class AccountOpeningController {

    private final CustomerAdministration customers;
    private final InstitutionAccounts accounts;
    private final ProductCatalogue products;

    public AccountOpeningController(
            CustomerAdministration customers, InstitutionAccounts accounts, ProductCatalogue products) {
        this.customers = customers;
        this.accounts = accounts;
        this.products = products;
    }

    /**
     * Opens an account and gives it a number.
     *
     * <p>{@code customers:link} rather than a new permission: the act is the same one that surface
     * already governs, now able to complete itself.
     */
    @PostMapping("/customers/{customerId}/accounts/open")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerAdministration.OpenedAccount open(
            @PathVariable UUID customerId, @RequestBody OpenAccount request) {
        var identity = Authorization.require("customers:link");

        String currency = request.currency() == null ? null : request.currency().trim().toUpperCase(Locale.ROOT);
        if (currency == null || currency.length() != 3) {
            throw new OpeningRefused("currency must be a 3-letter ISO 4217 code");
        }

        // Required, because an account without one cannot transact: the money path reads the
        // product from the account and refuses when there is none. Establishing it here is the only
        // moment somebody is present who knows what the customer is opening.
        String productCode = request.productCode() == null ? null : request.productCode().trim();
        if (productCode == null || productCode.isBlank()) {
            throw new OpeningRefused("productCode is required — an account is held under a product");
        }

        // And it must name a product the catalogue actually has. A typo'd code used to be accepted
        // verbatim and surfaced only on the money path — PRODUCT_NOT_FOUND on every transaction,
        // against a link whose product_code nothing can edit. This controller is where the check
        // belongs: it is the composition point that may see Product, which Customer's own bare
        // link route cannot (ADR 0006).
        if (!products.exists(identity.tenantId(), productCode)) {
            throw new UnknownProduct(productCode);
        }

        // The ledger holds no PII, so what identifies the account there is the institution's own
        // customer number — which also makes the open idempotent per customer and currency.
        String reference = customers.externalRefOf(identity.tenantId(), customerId);
        if (reference == null) {
            throw new OpeningRefused("no such customer");
        }

        InstitutionAccounts.Opened opened =
                accounts.openForCustomer(identity.tenantId(), "customer:" + reference, currency);
        if (!opened.ok()) {
            throw new OpeningRefused("the account could not be opened: " + opened.failure());
        }

        return customers.linkWithNumber(
                identity.tenantId(),
                customerId,
                opened.ledgerAccountId(),
                currency,
                request.role() == null || request.role().isBlank() ? "PRIMARY" : request.role(),
                productCode,
                request.accountNumber());
    }

    /** How customers and their accounts are numbered, and what the next of each would be. */
    @GetMapping("/customer-numbering")
    public List<CustomerAdministration.NumberSeries> numbering() {
        var identity = Authorization.require("customers:read");
        return List.of(
                customers.numbering(identity.tenantId(), "CUSTOMER"),
                customers.numbering(identity.tenantId(), "ACCOUNT"));
    }

    /**
     * Changes one of the series.
     *
     * <p>{@code nextValue} is settable for the reason staff numbering gives: an institution moving
     * onto this platform arrives with numbers already issued, and a counter that can only start at
     * one would collide with every one of them. Settable <em>forward</em> only — the record layer
     * refuses a rewind, because re-issuing spent numbers is a collision at every opening after.
     *
     * <p>{@code org:manage}, not {@code customers:create}: how the institution numbers its accounts
     * is institution configuration, the same category as its organizational units — not something a
     * teller-grade grant that can register customers should be able to move.
     */
    @PutMapping("/customer-numbering/{series}")
    public CustomerAdministration.NumberSeries setNumbering(
            @PathVariable String series, @RequestBody Numbering request) {
        var identity = Authorization.require("org:manage");

        String wanted = String.valueOf(series).trim().toUpperCase(Locale.ROOT);
        if (!wanted.equals("CUSTOMER") && !wanted.equals("ACCOUNT")) {
            throw new OpeningRefused("series must be CUSTOMER or ACCOUNT");
        }
        if (request.width() < 1 || request.width() > 20) {
            throw new OpeningRefused("width must be between 1 and 20");
        }
        if (request.nextValue() < 1) {
            throw new OpeningRefused("nextValue must be 1 or more");
        }
        return customers.setNumbering(
                identity.tenantId(),
                wanted,
                request.prefix(),
                request.width(),
                request.nextValue(),
                Authorization.initiatedBy());
    }

    /**
     * @param productCode what the account is held under. Decides which fee and limit rules every
     *     transaction on it is judged by, so it is required rather than defaulted — a default here
     *     would be the platform guessing at pricing on a customer's behalf.
     * @param role PRIMARY unless the institution distinguishes several accounts per customer
     */
    /**
     * @param accountNumber the institution's own number for this account, or null to be given the
     *     next one from its ACCOUNT series
     */
    public record OpenAccount(String currency, String role, String productCode, String accountNumber) {}

    public record Numbering(String prefix, int width, long nextValue) {}

    /** The account cannot be opened as asked. */
    public static class OpeningRefused extends RuntimeException {
        public OpeningRefused(String message) {
            super(message);
        }
    }

    /**
     * The named product is not in the catalogue.
     *
     * <p>Its own type rather than an {@code OpeningRefused}, because the remedy differs and so must
     * the code: {@code ACCOUNT_NOT_OPENED} means fix the request, this means fix the product code —
     * and it answers with the same {@code PRODUCT_NOT_FOUND} the money path would eventually have
     * used, so a caller handles one code for one fact wherever it surfaces.
     */
    public static class UnknownProduct extends RuntimeException {
        public final String productCode;

        public UnknownProduct(String productCode) {
            super("no product answers to code " + productCode);
            this.productCode = productCode;
        }
    }

    /** Kept beside the controller: one refusal shape for one surface. */
    @org.springframework.web.bind.annotation.RestControllerAdvice(assignableTypes = AccountOpeningController.class)
    public static class Errors {

        @org.springframework.web.bind.annotation.ExceptionHandler(OpeningRefused.class)
        public org.springframework.http.ResponseEntity<Map<String, Object>> refused(OpeningRefused e) {
            return org.springframework.http.ResponseEntity.unprocessableEntity()
                    .body(Map.of("code", "ACCOUNT_NOT_OPENED", "message", e.getMessage(), "details", Map.of()));
        }

        /**
         * The refusals raised by the record layer rather than by this controller.
         *
         * <p>Absent until now, so both arrived as a 500. Customer's own advice handles them, but it
         * is scoped to {@code CustomerController} and this is a different surface — an advice bound
         * to one controller does not cover another that happens to call the same code.
         *
         * <p>Code only, no message: the sentence belongs to the client (hard rule 8), and these two
         * are told apart because their remedies are — one means the customer already holds this
         * account, the other means the number they supplied belongs to somebody else.
         */
        @org.springframework.web.bind.annotation.ExceptionHandler(
                CustomerAdministration.AccountNumberTaken.class)
        public org.springframework.http.ResponseEntity<Map<String, Object>> numberTaken() {
            return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT)
                    .body(Map.of("code", "ACCOUNT_NUMBER_TAKEN", "details", Map.of()));
        }

        @org.springframework.web.bind.annotation.ExceptionHandler(
                CustomerAdministration.AccountAlreadyHeld.class)
        public org.springframework.http.ResponseEntity<Map<String, Object>> alreadyHeld() {
            return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT)
                    .body(Map.of("code", "ACCOUNT_ALREADY_HELD", "details", Map.of()));
        }

        /** The same code the money path uses for the same fact, caught before the account exists. */
        @org.springframework.web.bind.annotation.ExceptionHandler(UnknownProduct.class)
        public org.springframework.http.ResponseEntity<Map<String, Object>> unknownProduct(UnknownProduct e) {
            return org.springframework.http.ResponseEntity.unprocessableEntity()
                    .body(Map.of(
                            "code", "PRODUCT_NOT_FOUND",
                            "message", e.getMessage(),
                            "details", Map.of("field", "productCode", "supplied", e.productCode)));
        }

        /**
         * A series asked to wind backwards. {@code details.current} says where forward starts, so
         * the client can render the refusal without a second read.
         */
        @org.springframework.web.bind.annotation.ExceptionHandler(
                CustomerAdministration.NumberingRewind.class)
        public org.springframework.http.ResponseEntity<Map<String, Object>> rewind(
                CustomerAdministration.NumberingRewind e) {
            return org.springframework.http.ResponseEntity.unprocessableEntity()
                    .body(Map.of(
                            "code", "COMMAND_INVALID",
                            "message", e.getMessage(),
                            // Counters as decimal strings, like every numeric fact this API emits.
                            "details",
                                    Map.of(
                                            "field", "nextValue",
                                            "supplied", Long.toString(e.supplied),
                                            "current", Long.toString(e.current))));
        }
    }
}
