package org.elyonar.fincore.core.orchestration.internal.services;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.core.orchestration.api.ProductDecision;
import org.elyonar.fincore.core.orchestration.api.ProductDecisions;
import org.elyonar.fincore.core.orchestration.api.ProductRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The pricing decision, now over the wire (ADR 0020).
 *
 * <p>This is the call the whole extraction was argued on. It is asked once per transaction, holds
 * no transaction while it runs, and its answer is a pure function of a published version that
 * {@code reject_published_edit} and {@code reject_published_rule_write} make immutable in the
 * database. That is what makes it safe to ask across a network and what will make it safe to cache
 * by {@code (productCode, productVersion)} — publishing changes the version, which changes the key.
 *
 * <p>No cache yet, deliberately. A cache added in the same change as the extraction would mean
 * debugging two new things at once if a price came back wrong, and the correct-but-slower version
 * is the one worth having first.
 *
 * <p><strong>A refusal is a 200 with a reason.</strong> "This product forbids this operation for
 * this tier" is an answer, so the service returns it as one and this maps it to the same
 * {@link ProductDecision.Refusal} the in-process evaluator produced. Only a genuine inability to
 * ask throws, and it fails closed: an unreachable pricing service refuses the transaction rather
 * than pricing it at nothing.
 */
@Component
public class HttpProductDecisions implements ProductDecisions {

    private final ServiceCall call;
    private final String baseUrl;

    public HttpProductDecisions(
            ServiceCall call, @Value("${fincore.core.product.base-url:http://localhost:8084}") String baseUrl) {
        this.call = call;
        this.baseUrl = baseUrl;
    }

    @Override
    public ProductDecision evaluate(ProductRequest request) {
        // The tenant travels in the token, never in the body — the service ignores a body tenant
        // and substitutes the validated one, so sending it would only invite somebody to trust it.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productCode", request.productCode());
        body.put("operation", request.operation().name());
        body.put("kycTier", request.kycTier());
        body.put("channel", request.channel());
        body.put("amountMinor", request.amountMinor());
        body.put("currency", request.currency());

        JsonNode answer = call.post(request.tenantId(), baseUrl, "/v1/decisions/evaluate", body);
        if (answer == null) {
            // A 404 from this endpoint is not "no such product" — the evaluator answers that with a
            // PRODUCT_NOT_FOUND refusal in a 200. It means the tenant is not registered with the
            // pricing service, which is a provisioning fault and must not price anything.
            throw new ServiceCall.Unavailable("pricing service does not know this tenant", null);
        }

        if (!answer.path("permitted").asBoolean()) {
            String refusal = answer.path("refusal").asString();
            // The version travels with the refusal: an operator asking why a transaction was
            // refused needs to know which version refused it, and a refusal that names no version
            // sends them to whichever one is current now rather than the one that decided.
            return ProductDecision.refused(
                    refusal == null || refusal.isBlank()
                            ? ProductDecision.Refusal.PRODUCT_NOT_FOUND
                            : ProductDecision.Refusal.valueOf(refusal),
                    answer.path("productVersion").asInt());
        }

        JsonNode feeAccount = answer.get("feeAccountId");
        JsonNode daily = answer.get("dailyLimitMinor");
        return ProductDecision.permitted(
                answer.path("feeMinor").asLong(),
                feeAccount == null || feeAccount.isNull() ? null : UUID.fromString(feeAccount.asString()),
                answer.path("limitMinor").asLong(),
                daily == null || daily.isNull() ? null : daily.asLong(),
                answer.path("productVersion").asInt());
    }
}
