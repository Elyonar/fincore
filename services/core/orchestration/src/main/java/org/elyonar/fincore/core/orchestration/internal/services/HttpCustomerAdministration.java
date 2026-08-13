package org.elyonar.fincore.core.orchestration.internal.services;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.core.orchestration.api.CustomerAdministration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Customer administration Core still needs, over the wire (ADR 0020).
 *
 * <p>Three of these belong to account opening, which stays in Core because it is a genuine
 * composition and not a customer operation: only Orchestration may address the ledger, so Core
 * opens the ledger account, confirms the product with Product, and then records the holding here.
 * Splitting that across services would have left the ledger account and the customer's claim on it
 * being created by two different callers with no one owning the outcome.
 *
 * <p>{@code linkWithNumber} is the write that matters. The account number is claimed on the far
 * side, inside Customer's own transaction, precisely so two tellers opening accounts at the same
 * moment cannot be issued the same one. Moving the claim to Core would have turned a database
 * guarantee into a race.
 *
 * <p>Fails closed like the rest: an unreachable Customer service throws rather than returning a
 * number nobody reserved.
 */
@Component
public class HttpCustomerAdministration implements CustomerAdministration {

    private final ServiceCall call;
    private final String baseUrl;

    public HttpCustomerAdministration(
            ServiceCall call, @Value("${fincore.core.customer.base-url:http://localhost:8085}") String baseUrl) {
        this.call = call;
        this.baseUrl = baseUrl;
    }

    @Override
    public NumberSeries numbering(UUID tenantId, String series) {
        JsonNode body = call.get(tenantId, baseUrl, "/v1/numbering/" + series);
        return body == null ? null : series(body);
    }

    @Override
    public NumberSeries setNumbering(
            UUID tenantId, String series, String prefix, int width, long nextValue, String updatedBy) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("prefix", prefix);
        request.put("width", width);
        request.put("nextValue", nextValue);
        request.put("updatedBy", updatedBy);
        JsonNode body = call.post(tenantId, baseUrl, "/v1/numbering/" + series, request);
        if (body == null) {
            throw new ServiceCall.Unavailable("customer service does not know this series", null);
        }
        return series(body);
    }

    @Override
    public OpenedAccount linkWithNumber(
            UUID tenantId,
            UUID customerId,
            UUID ledgerAccountId,
            String currency,
            String role,
            String productCode,
            String accountNumber) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("ledgerAccountId", ledgerAccountId == null ? null : ledgerAccountId.toString());
        request.put("currency", currency);
        request.put("role", role);
        request.put("productCode", productCode);
        request.put("accountNumber", accountNumber);

        JsonNode body = call.post(tenantId, baseUrl, "/v1/customers/" + customerId + "/accounts/link", request);
        if (body == null) {
            // The customer vanished between Core opening the ledger account and recording who holds
            // it. The ledger account now exists and nobody holds it, which is a reconciliation
            // finding rather than something to paper over here.
            throw new ServiceCall.Unavailable("no such customer when recording the account", null);
        }
        JsonNode code = body.get("productCode");
        return new OpenedAccount(
                UUID.fromString(body.path("ledgerAccountId").asString()),
                body.path("accountNumber").asString(),
                body.path("currency").asString(),
                body.path("role").asString(),
                code == null || code.isNull() ? null : code.asString());
    }

    @Override
    public String externalRefOf(UUID tenantId, UUID customerId) {
        JsonNode body = call.get(tenantId, baseUrl, "/v1/customers/" + customerId);
        if (body == null) {
            return null;
        }
        JsonNode ref = body.get("externalRef");
        return ref == null || ref.isNull() ? null : ref.asString();
    }

    private static NumberSeries series(JsonNode body) {
        return new NumberSeries(
                body.path("series").asString(),
                body.path("prefix").asString(),
                body.path("width").asInt(),
                body.path("nextValue").asLong(),
                body.path("preview").asString());
    }
}
