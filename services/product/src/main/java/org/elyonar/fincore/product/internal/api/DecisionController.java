package org.elyonar.fincore.product.internal.api;

import java.util.UUID;
import org.elyonar.fincore.auth.Authorization;
import org.elyonar.fincore.product.api.ProductDecision;
import org.elyonar.fincore.product.api.ProductDecisions;
import org.elyonar.fincore.product.api.ProductRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The one question the money path asks, over the wire.
 *
 * <p>This endpoint is the whole reason the extraction is safe. It is read-only, it holds no
 * transaction, and its answer is a pure function of a published version — which
 * {@code reject_published_edit} and {@code reject_published_rule_write} make immutable in the
 * database rather than by convention. Core may therefore cache a decision by
 * {@code (productCode, productVersion)} and never invalidate it on a timer: publishing changes the
 * version, which changes the key.
 *
 * <p><strong>The tenant comes from the token, never from the body.</strong> {@code ProductRequest}
 * carries a {@code tenantId} because it was an in-process value object a moment ago, and a caller
 * able to put a tenant in a body is a caller reading another institution's prices. The body's
 * field is ignored and the validated token's tenant is substituted, which is the same rule every
 * other surface on this platform follows.
 *
 * <p><strong>A refusal is a 200.</strong> "This product forbids this operation for this tier" is
 * an answer, not an error — the decision record carries {@code permitted} and a {@code Refusal},
 * and Core turns those into its own coded 4xx for the customer-facing call. Returning a 4xx here
 * would make an ordinary business outcome indistinguishable from a malformed request, which is the
 * distinction hard rule 9 exists to protect.
 */
@Tag(name = "Decisions", description = "Pricing and limit decisions for the money path")
@RestController
@RequestMapping("/v1/decisions")
public class DecisionController {

    private final ProductDecisions decisions;

    public DecisionController(ProductDecisions decisions) {
        this.decisions = decisions;
    }

    @PostMapping("/evaluate")
    public ProductDecision evaluate(@RequestBody Evaluate request) {
        var identity = Authorization.require("products:read");
        return decisions.evaluate(
                new ProductRequest(
                        identity.tenantId(),
                        request.productCode(),
                        ProductRequest.Operation.valueOf(request.operation()),
                        request.kycTier(),
                        request.channel(),
                        request.amountMinor(),
                        request.currency()));
    }

    /**
     * The wire shape, deliberately not {@code ProductRequest} itself.
     *
     * <p>It carries no tenant. Binding the domain record directly would have published a settable
     * {@code tenantId} on the request body of the busiest endpoint on the platform, and the only
     * thing standing between that and a cross-tenant price read would have been the handler
     * remembering to overwrite it.
     *
     * @param operation one of {@code DEPOSIT}, {@code WITHDRAWAL}, {@code TRANSFER}
     */
    public record Evaluate(
            String productCode,
            String operation,
            String kycTier,
            String channel,
            long amountMinor,
            String currency) {}
}
