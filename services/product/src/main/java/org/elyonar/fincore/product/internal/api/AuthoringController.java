package org.elyonar.fincore.product.internal.api;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.elyonar.fincore.auth.Authorization;
import org.elyonar.fincore.product.api.ProductAuthoring;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Drafting and pricing a version, over the wire.
 *
 * <p>The counterpart to Core's {@code PricingController}, which keeps the half of this work that
 * only Core can do — validating that a fee rule names one of the institution's own accounts, which
 * lives in Core's {@code internal_accounts} and which Product may not reach for (ADR 0020). Core
 * validates, then writes through to here. This surface therefore trusts the account id it is given
 * and is not, on its own, the whole control.
 *
 * <p><strong>Not the same shape as the catalogue.</strong> Products are addressed by code on
 * {@code ProductController} because that is what a human and the money path both use; versions are
 * addressed by product <em>id</em> here because authoring already holds one and a code is a
 * mutable-looking handle to hang a write off.
 *
 * <p>Refusals keep their meaning across the boundary. A published version being edited is a 409,
 * which is what {@code VersionPublished} was in process — the immutability trigger fires in the
 * database either way, and the status code is how that reaches Core's error mapping unchanged.
 */
@Tag(name = "Authoring", description = "Drafting versions and writing their fees and limits")
@RestController
@RequestMapping("/v1/products/{productId}/versions")
public class AuthoringController {

    private final ProductAuthoring authoring;

    public AuthoringController(ProductAuthoring authoring) {
        this.authoring = authoring;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Drafted draft(@PathVariable UUID productId, @RequestBody Draft request) {
        var identity = Authorization.require("products:create");
        /*
         * The drafter is the caller, unless the caller is Core forwarding somebody.
         *
         * Attribution here is not bookkeeping: whoever is recorded as drafting a version is the one
         * person who may not publish it. Get it wrong and maker-checker inverts — which is exactly
         * the bug `a_draft_belongs_to_the_person_who_drafted_it` was written about, where v2
         * inherited v1's author and let its real drafter sign it off.
         *
         * So the default is the identity on the request. Core may override it, because Core calls
         * this with its own service credential while acting for an administrator, and that
         * administrator is the real maker. A body that names nobody must never silently become
         * "nobody", which is what made the null here a 422 rather than a draft.
         */
        String author =
                request.author() == null || request.author().isBlank()
                        ? Authorization.initiatedBy()
                        : request.author();
        return new Drafted(authoring.draftNextVersion(identity.tenantId(), productId, request.copyFrom(), author));
    }

    @GetMapping("/{version}")
    public ProductAuthoring.VersionDetail read(@PathVariable UUID productId, @PathVariable int version) {
        var identity = Authorization.require("products:read");
        return authoring.read(identity.tenantId(), productId, version);
    }

    @PutMapping("/{version}/fee-rules")
    public Written setFeeRules(
            @PathVariable UUID productId, @PathVariable int version, @RequestBody FeeRules request) {
        var identity = Authorization.require("products:create");
        authoring.setFeeRules(identity.tenantId(), productId, version, request.rules());
        return new Written(version);
    }

    @PutMapping("/{version}/limit-rules")
    public Written setLimitRules(
            @PathVariable UUID productId, @PathVariable int version, @RequestBody LimitRules request) {
        var identity = Authorization.require("products:create");
        authoring.setLimitRules(identity.tenantId(), productId, version, request.rules());
        return new Written(version);
    }

    @PatchMapping("/{version}")
    public Scheduled setEffectiveFrom(
            @PathVariable UUID productId, @PathVariable int version, @RequestBody EffectiveFrom request) {
        var identity = Authorization.require("products:create");
        String scheduled = notBackdated(request.effectiveFrom());
        authoring.setEffectiveFrom(identity.tenantId(), productId, version, scheduled);
        // Echoes the normalised instant rather than what was typed: the caller asked for a moment
        // and this is the moment that was stored, in the one spelling everything downstream uses.
        return new Scheduled(version, scheduled);
    }

    /**
     * A schedule must be a real instant, and must not be in the past.
     *
     * <p>Backdating is refused rather than clamped. A version that claims to have been in effect
     * yesterday would price transactions that were already decided under something else, and no
     * amount of care afterwards can tell which rules actually judged them.
     *
     * <p>Two spellings are accepted because the database would accept either and they mean the same
     * moment: an offset date-time and a bare instant. Anything else is refused with what was
     * supplied and what was expected, so the fix is obvious from the response.
     */
    private static String notBackdated(String effectiveFrom) {
        if (effectiveFrom == null || effectiveFrom.isBlank()) {
            return null;
        }
        Instant moment;
        try {
            moment = OffsetDateTime.parse(effectiveFrom).toInstant();
        } catch (DateTimeParseException notAnOffset) {
            try {
                moment = Instant.parse(effectiveFrom);
            } catch (DateTimeParseException notAnInstant) {
                throw new ProductAuthoring.RulesInvalid(
                        org.elyonar.fincore.product.api.ProductErrorReason.EFFECTIVE_FROM_INVALID,
                        java.util.Map.of(
                                "effectiveFrom", effectiveFrom,
                                "expects", "an ISO-8601 instant, e.g. 2026-09-01T00:00:00Z"));
            }
        }
        if (moment.isBefore(Instant.now())) {
            throw new Backdated(effectiveFrom);
        }
        return moment.toString();
    }

    /**
     * Rules the evaluator would refuse, refused at authoring time instead.
     *
     * <p>A fee above one hundred per cent, a tier nobody can hold, a channel nothing sends: each
     * would store cleanly and then deny every transaction under the version, with no way to edit it
     * once published. 422 with the reason and the offending values, so an administrator sees which
     * rule and why rather than a generic rejection.
     */
    @ExceptionHandler(ProductAuthoring.RulesInvalid.class)
    public ResponseEntity<Invalid> rulesInvalid(ProductAuthoring.RulesInvalid e) {
        return ResponseEntity.unprocessableEntity()
                .body(new Invalid("RULES_INVALID", e.reason.name(), e.details));
    }

    /** Its own code, because "in the past" is a different fix from "not a date at all". */
    @ExceptionHandler(Backdated.class)
    public ResponseEntity<Invalid> backdated(Backdated e) {
        return ResponseEntity.unprocessableEntity()
                .body(new Invalid(
                        "EFFECTIVE_FROM_IN_THE_PAST", "EFFECTIVE_FROM_IN_THE_PAST",
                        java.util.Map.of("effectiveFrom", e.supplied)));
    }

    /** A published version is immutable, and the database says so before this does. */
    @ExceptionHandler(ProductAuthoring.VersionPublished.class)
    public ResponseEntity<Problem> published(ProductAuthoring.VersionPublished e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new Problem("VERSION_ALREADY_PUBLISHED", e.getMessage()));
    }

    @ExceptionHandler(ProductAuthoring.NoSuchVersion.class)
    public ResponseEntity<Problem> missing(ProductAuthoring.NoSuchVersion e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Problem("NO_SUCH_VERSION", e.getMessage()));
    }

    /** Two drafts of the same next version raced; the loser retries rather than overwriting. */
    @ExceptionHandler(ProductAuthoring.DraftConflict.class)
    public ResponseEntity<Problem> conflict(ProductAuthoring.DraftConflict e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Problem("DRAFT_CONFLICT", e.getMessage()));
    }

    public record Draft(Integer copyFrom, String author) {}

    public record Drafted(int version) {}

    public record Written(int version) {}

    public record Scheduled(int version, String effectiveFrom) {}

    public record FeeRules(List<ProductAuthoring.FeeRule> rules) {}

    public record LimitRules(List<ProductAuthoring.LimitRule> rules) {}

    public record EffectiveFrom(String effectiveFrom) {}

    /** A schedule that has already happened. Refused, never clamped. */
    public static class Backdated extends RuntimeException {
        public final transient String supplied;

        public Backdated(String supplied) {
            super("effectiveFrom is in the past: " + supplied);
            this.supplied = supplied;
        }
    }

    public record Problem(String code, String message) {}

    /** @param reason the {@code ProductErrorReason} name, so a caller renders from a code */
    public record Invalid(String code, String reason, java.util.Map<String, Object> details) {}
}
