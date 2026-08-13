package org.elyonar.fincore.core.orchestration.internal.services;

import java.util.UUID;
import org.elyonar.fincore.core.orchestration.api.ProductCatalogue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * "Is this a real product?", over the wire (ADR 0020).
 *
 * <p>Asked when an account is opened, not when money moves — account opening names the product the
 * account will be held under, and a typo there produces an account nothing can ever price. It is
 * the cheap check that stops a much more expensive problem later.
 *
 * <p>Fails closed like everything else on this path: an unreachable catalogue means the product
 * cannot be confirmed, and an account whose product cannot be confirmed is not opened. Answering
 * "true" on a timeout would create exactly the unpriceable account this exists to prevent.
 */
@Component
public class HttpProductCatalogue implements ProductCatalogue {

    private final ServiceCall call;
    private final String baseUrl;

    public HttpProductCatalogue(
            ServiceCall call, @Value("${fincore.core.product.base-url:http://localhost:8084}") String baseUrl) {
        this.call = call;
        this.baseUrl = baseUrl;
    }

    @Override
    public boolean exists(UUID tenantId, String productCode) {
        if (productCode == null || productCode.isBlank()) {
            return false;
        }
        JsonNode found = call.get(tenantId, baseUrl, "/v1/products/" + productCode);
        return found != null;
    }
}
