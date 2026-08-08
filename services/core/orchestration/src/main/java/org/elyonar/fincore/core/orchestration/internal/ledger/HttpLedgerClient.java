package org.elyonar.fincore.core.orchestration.internal.ledger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.core.orchestration.api.LedgerOutcome;
import org.elyonar.fincore.core.orchestration.api.LedgerPosting;
import org.elyonar.fincore.core.orchestration.api.LedgerRead;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The Ledger client. The only outbound dependency in Core, and it lives here because
 * {@code orchestration} is the only module permitted to call the Ledger's write API.
 *
 * <p>Its whole job is to turn a call into a {@link LedgerOutcome} without ever losing the
 * distinction between "nothing was sent" and "we do not know". Everything else in the saga engine
 * trusts that distinction, so this class never lets an exception escape uninterpreted — a caller
 * receiving a raw {@code IOException} would have to classify it itself, and classification in two
 * places is classification that disagrees.
 */
public class HttpLedgerClient implements LedgerClient {

    private final RestClient http;
    private final ObjectMapper json;

    public HttpLedgerClient(String baseUrl, Duration connectTimeout, Duration readTimeout, ObjectMapper json) {
        this(baseUrl, connectTimeout, readTimeout, json, null);
    }

    public HttpLedgerClient(
            String baseUrl, Duration connectTimeout, Duration readTimeout, ObjectMapper json, String serviceToken) {
        // Timeouts are not optional. Without a read timeout an unresponsive Ledger holds a worker
        // thread indefinitely, which turns one partner's stall into Core's outage.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeout.toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());

        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.json = json;
        this.serviceToken = serviceToken == null || serviceToken.isBlank() ? null : serviceToken;
    }

    /**
     * Core's own credential for addressing the ledger in jwt mode (ADR 0014) — a service token
     * for the {@code core} client. Null in dev/header mode, where the tenant header suffices.
     */
    private final String serviceToken;

    /**
     * Identity on every outbound call: the tenant header the ledger scopes on (asserted by this
     * verified caller for its system jobs), Core's service credential when configured, and — when
     * this thread is serving an authenticated request — the originating user's token, forwarded so
     * the ledger records attribution as a verified fact (outbound propagation, ADR 0014).
     */
    private void identity(org.springframework.http.HttpHeaders headers, UUID tenantId) {
        headers.set(TENANT_HEADER, tenantId.toString());
        if (serviceToken != null) {
            headers.set("Authorization", "Bearer " + serviceToken);
        }
        org.elyonar.fincore.auth.Authorization.bearer()
                .ifPresent(bearer -> headers.set("X-Forwarded-Authorization", "Bearer " + bearer));
    }

    /**
     * The tenant header the Ledger scopes on.
     *
     * <p>Interim, and the Ledger says so too: until Identity issues tokens the tenant arrives in a
     * header, which is not authentication. It is read in exactly one place on each side, so
     * replacing it with a propagated identity later touches this constant and nothing else.
     */
    private static final String TENANT_HEADER = "X-Tenant-Id";

    @Override
    public LedgerOutcome post(UUID tenantId, LedgerPosting posting) {
        if (!posting.balances()) {
            // Ours to catch. The Ledger would reject it, but an unbalanced posting is a Core defect
            // and it should surface as one rather than as a business error from downstream.
            throw new IllegalArgumentException(
                    "posting does not balance: debits=" + posting.totalFor(LedgerPosting.Direction.DEBIT)
                            + " credits=" + posting.totalFor(LedgerPosting.Direction.CREDIT));
        }
        return exchange(tenantId, "/v1/transactions", body(posting));
    }

    @Override
    public LedgerOutcome reverse(
            UUID tenantId, UUID ledgerTransactionId, String idempotencyKey, String initiatedBy) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idempotencyKey", idempotencyKey);
        body.put("initiatedBy", initiatedBy);
        return exchange(tenantId, "/v1/transactions/" + ledgerTransactionId + "/reverse", body);
    }

    private LedgerOutcome exchange(UUID tenantId, String path, Map<String, Object> body) {
        try {
            var response =
                    http.post()
                            .uri(path)
                            .headers(h -> identity(h, tenantId))
                            .body(body)
                            // Every status is handled here rather than thrown, so a 4xx and a 5xx
                            // travel the same code path into the classifier and cannot be
                            // accidentally treated alike by an exception handler.
                            .exchange((request, res) -> new RawResponse(res.getStatusCode().value(), readBody(res)), false);

            JsonNode parsed = parse(response.body());
            if (parsed == null && response.status() >= 200 && response.status() < 300) {
                // A success we cannot read is not a success we can record.
                return new LedgerOutcome.Unknown("unparseable 2xx body");
            }
            return OutcomeClassifier.ofResponse(
                    response.status(),
                    textAt(parsed, "code"),
                    // `detail` last: the Ledger's error contract carries the relevant identifier
                    // there, and on ALREADY_REVERSED that identifier is the winning reversal.
                    // Without reading it the outcome classifies as UNKNOWN and a losing reversal
                    // retries forever instead of settling — found by the contract suite, because a
                    // stub answered with whatever field this client happened to look for.
                    uuidAt(parsed, "transactionId", "reversalTransactionId", "detail"));

        } catch (RuntimeException e) {
            // Includes connect failures, read timeouts and resets. The classifier unwraps causes,
            // so a client library wrapping them cannot hide which one happened.
            return OutcomeClassifier.ofTransportFailure(e);
        }
    }

    @Override
    public LedgerRead read(UUID tenantId, UUID ledgerTransactionId) {
        try {
            var response =
                    http.get()
                            .uri("/v1/transactions/" + ledgerTransactionId)
                            .headers(h -> identity(h, tenantId))
                            .exchange((request, res) -> new RawResponse(res.getStatusCode().value(), readBody(res)), false);

            if (response.status() == 404) {
                return new LedgerRead.NotFound();
            }
            if (response.status() < 200 || response.status() >= 300) {
                return new LedgerRead.Unknown("status " + response.status());
            }
            JsonNode parsed = parse(response.body());
            if (parsed == null || parsed.get("status") == null || parsed.get("entries") == null) {
                return new LedgerRead.Unknown("unparseable 2xx body");
            }
            long debits = 0;
            for (JsonNode entry : parsed.get("entries")) {
                if ("DEBIT".equals(entry.path("direction").asString())) {
                    // The wire amount is minor units as a decimal string (the ledger's Money.toWire).
                    debits += Long.parseLong(entry.path("amountMinor").asString());
                }
            }
            return new LedgerRead.Found(parsed.path("status").asString(), debits);
        } catch (RuntimeException e) {
            return new LedgerRead.Unknown(e.getClass().getSimpleName());
        }
    }

    @Override
    public LedgerClient.RawRead get(UUID tenantId, String pathAndQuery) {
        try {
            var response =
                    http.get()
                            .uri(pathAndQuery)
                            .headers(h -> identity(h, tenantId))
                            .exchange((request, res) -> new RawResponse(res.getStatusCode().value(), readBody(res)), false);
            return new LedgerClient.RawRead(response.status(), response.body());
        } catch (RuntimeException e) {
            // Transport failure: status 0, no body. The caller answers 503 — "could not ask" is
            // an answer, never an invented result.
            return new LedgerClient.RawRead(0, null);
        }
    }

    private Map<String, Object> body(LedgerPosting posting) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (LedgerPosting.Entry entry : posting.entries()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("accountId", entry.accountId().toString());
            e.put("direction", entry.direction().name());
            // Decimal string, matching the rule every monetary field on the platform follows.
            e.put("amountMinor", Long.toString(entry.amountMinor()));
            e.put("currency", entry.currency());
            entries.add(e);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idempotencyKey", posting.idempotencyKey());
        body.put("initiatedBy", posting.initiatedBy());
        body.put("description", posting.description());
        body.put("entries", entries);
        return body;
    }

    private static String readBody(org.springframework.http.client.ClientHttpResponse response) {
        try (var in = response.getBody()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static String textAt(JsonNode node, String field) {
        return node == null || !node.hasNonNull(field) ? null : node.get(field).asText();
    }

    private static UUID uuidAt(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            if (node.hasNonNull(field)) {
                try {
                    return UUID.fromString(node.get(field).asText());
                } catch (IllegalArgumentException ignored) {
                    // A malformed id is not an id. Fall through so the caller sees "no id", which
                    // classifies as UNKNOWN rather than as a success pointing nowhere.
                }
            }
        }
        return null;
    }

    private record RawResponse(int status, String body) {}
}
