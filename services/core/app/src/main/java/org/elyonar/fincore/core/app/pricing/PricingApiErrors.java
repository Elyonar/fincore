package org.elyonar.fincore.core.app.pricing;

import java.util.Map;
import org.elyonar.fincore.core.product.api.ProductAuthoring;
import org.elyonar.fincore.core.product.api.ProductErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The pricing surface's refusals.
 *
 * <p>Scoped to its own controller rather than added to Core's global advice, because two of these
 * are about a workflow rather than about money: editing a live version is a mistake with an obvious
 * remedy — draft the next one — and the message says so instead of naming a constraint.
 */
@RestControllerAdvice(assignableTypes = PricingController.class)
public class PricingApiErrors {

    /** No such product, or no such version of it. Another tenant's is indistinguishable. */
    @ExceptionHandler(ProductAuthoring.NoSuchVersion.class)
    public ResponseEntity<Map<String, Object>> notFound(ProductAuthoring.NoSuchVersion e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of(
                        "code", "PRODUCT_VERSION_NOT_FOUND",
                        "message", "no such product version",
                        "details", Map.of()));
    }

    /**
     * The version is live.
     *
     * <p>409 rather than 422: nothing about the request is malformed, the target is simply in a
     * state that refuses it, and the caller resolves it by acting — drafting the next version —
     * rather than by correcting a field.
     */
    @ExceptionHandler(ProductAuthoring.VersionPublished.class)
    public ResponseEntity<Map<String, Object>> published(ProductAuthoring.VersionPublished e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "code", "VERSION_ALREADY_PUBLISHED",
                        "message",
                                "version " + e.version + " is live and priced transactions that already"
                                        + " happened. Draft the next version to change a price.",
                        "details", Map.of("version", e.version)));
    }

    /**
     * A draft dated before it existed.
     *
     * <p>Code only, no message. {@code EFFECTIVE_FROM_IN_THE_PAST} is already in api.md, already in
     * admin-surface.md, and the portal already has a sentence for it — the whole contract survived
     * the move of this route between controllers, and only the enforcement went missing.
     */
    @ExceptionHandler(PricingController.EffectiveFromInThePast.class)
    public ResponseEntity<Map<String, Object>> backdated(PricingController.EffectiveFromInThePast e) {
        return ResponseEntity.unprocessableEntity()
                .body(Map.of(
                        "code", ProductErrorCode.EFFECTIVE_FROM_IN_THE_PAST.code(),
                        "message", "a version may not be dated before it existed",
                        "details", Map.of()));
    }

    /** A rule naming an account the institution has not opened, or has opened for something else. */
    @ExceptionHandler(PricingController.PricingRefused.class)
    public ResponseEntity<Map<String, Object>> refused(PricingController.PricingRefused e) {
        return ResponseEntity.unprocessableEntity()
                .body(Map.of(
                        "code", ProductErrorCode.PRICING_ACCOUNT_INVALID.code(),
                        "message", e.getMessage(),
                        "details", Map.of()));
    }
}
