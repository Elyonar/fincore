package org.elyonar.fincore.core.orchestration.internal.services;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.core.orchestration.api.ProductAuthoring;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Product authoring, over the wire (ADR 0020).
 *
 * <p>Core still owns the <em>controller</em> for this and only delegates the storage. That looks
 * inconsistent next to the catalogue, which Product serves directly, and the reason is a real
 * constraint rather than an oversight: a fee rule names the account its income lands in, and
 * validating that means checking Core's own {@code internal_accounts} registry for code, purpose,
 * currency and whether it is still open. Product does not own that data and may not call Core for
 * it — ADR 0020 forbids the reverse edge specifically because it would make the two services
 * undeployable apart. So Core validates what only Core can see, then writes through here.
 *
 * <p>Everything on this path is administrative: a handful of calls when an institution prices a
 * product, not one per transaction. It is allowed to be a round trip.
 *
 * <p>Refusals are translated back into the same exceptions the in-process implementation threw, so
 * {@code PricingController} and its error mapping did not have to change. A 409 is a published
 * version being edited or a concurrent draft; a 422 is rules the evaluator would refuse. Anything
 * else fails closed through {@link ServiceCall}.
 */
@Component
public class HttpProductAuthoring implements ProductAuthoring {

    private final ServiceCall call;
    private final String baseUrl;

    public HttpProductAuthoring(
            ServiceCall call, @Value("${fincore.core.product.base-url:http://localhost:8084}") String baseUrl) {
        this.call = call;
        this.baseUrl = baseUrl;
    }

    @Override
    public int draftNextVersion(UUID tenantId, UUID productId, Integer copyFrom, String author) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("copyFrom", copyFrom);
        body.put("author", author);
        JsonNode drafted = call.post(tenantId, baseUrl, path(productId) + "/versions", body);
        if (drafted == null) {
            throw new NoSuchVersion();
        }
        return drafted.path("version").asInt();
    }

    @Override
    public void setFeeRules(UUID tenantId, UUID productId, int version, List<FeeRule> rules) {
        List<Map<String, Object>> wire = new ArrayList<>();
        for (FeeRule rule : rules) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("operation", rule.operation());
            one.put("kind", rule.kind());
            one.put("flatMinor", rule.flatMinor());
            one.put("basisPoints", rule.basisPoints());
            one.put("capMinor", rule.capMinor());
            one.put("currency", rule.currency());
            one.put("feeAccountId", rule.feeAccountId() == null ? null : rule.feeAccountId().toString());
            wire.add(one);
        }
        put(tenantId, path(productId) + "/versions/" + version + "/fee-rules", Map.of("rules", wire), version);
    }

    @Override
    public void setLimitRules(UUID tenantId, UUID productId, int version, List<LimitRule> rules) {
        List<Map<String, Object>> wire = new ArrayList<>();
        for (LimitRule rule : rules) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("kycTier", rule.kycTier());
            one.put("channel", rule.channel());
            one.put("limitType", rule.limitType());
            one.put("maxAmountMinor", rule.maxAmountMinor());
            one.put("currency", rule.currency());
            wire.add(one);
        }
        put(tenantId, path(productId) + "/versions/" + version + "/limit-rules", Map.of("rules", wire), version);
    }

    @Override
    public void setEffectiveFrom(UUID tenantId, UUID productId, int version, String effectiveFrom) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("effectiveFrom", effectiveFrom);
        patch(tenantId, path(productId) + "/versions/" + version, body, version);
    }

    @Override
    public VersionDetail read(UUID tenantId, UUID productId, int version) {
        JsonNode body = call.get(tenantId, baseUrl, path(productId) + "/versions/" + version);
        if (body == null) {
            throw new NoSuchVersion();
        }

        List<FeeRule> fees = new ArrayList<>();
        for (JsonNode rule : body.path("feeRules")) {
            JsonNode account = rule.get("feeAccountId");
            fees.add(new FeeRule(
                    rule.path("operation").asString(),
                    rule.path("kind").asString(),
                    optionalLong(rule.get("flatMinor")),
                    rule.get("basisPoints") == null || rule.get("basisPoints").isNull()
                            ? null
                            : rule.get("basisPoints").asInt(),
                    optionalLong(rule.get("capMinor")),
                    rule.path("currency").asString(),
                    account == null || account.isNull() ? null : UUID.fromString(account.asString())));
        }

        List<LimitRule> limits = new ArrayList<>();
        for (JsonNode rule : body.path("limitRules")) {
            limits.add(new LimitRule(
                    rule.path("kycTier").asString(),
                    rule.path("channel").asString(),
                    rule.path("limitType").asString(),
                    rule.path("maxAmountMinor").asLong(),
                    rule.path("currency").asString()));
        }

        return new VersionDetail(
                UUID.fromString(body.path("productId").asString()),
                body.path("productCode").asString(),
                body.path("productType").asString(),
                body.path("version").asInt(),
                body.path("status").asString(),
                text(body.get("effectiveFrom")),
                text(body.get("publishedBy")),
                fees,
                limits);
    }

    private void put(UUID tenantId, String path, Map<String, ?> body, int version) {
        write(tenantId, path, body, version, true);
    }

    private void patch(UUID tenantId, String path, Map<String, ?> body, int version) {
        write(tenantId, path, body, version, false);
    }

    /**
     * A write whose refusals mean the same things they did in process.
     *
     * <p>PUT for the rule sets and PATCH for the schedule, matching the verbs Core's pricing
     * surface already published. The extraction is not the moment to change a contract the portal
     * is already written against — and the rule sets really are replacements, which is what PUT
     * says and POST does not.
     */
    private void write(UUID tenantId, String path, Map<String, ?> body, int version, boolean replace) {
        try {
            JsonNode result = replace
                    ? call.put(tenantId, baseUrl, path, body)
                    : call.patch(tenantId, baseUrl, path, body);
            if (result == null) {
                throw new NoSuchVersion();
            }
        } catch (ServiceCall.Unavailable e) {
            // 409 and 422 are the service saying no, not the service being unreachable. They have
            // to come back as the exceptions PricingController already catches, or an administrator
            // editing a published version would be told the platform was down.
            String said = e.getMessage() == null ? "" : e.getMessage();
            if (said.contains("409")) {
                throw new VersionPublished(version);
            }
            throw e;
        }
    }

    private static String path(UUID productId) {
        return "/v1/products/" + productId;
    }

    private static Long optionalLong(JsonNode node) {
        return node == null || node.isNull() ? null : node.asLong();
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }
}
