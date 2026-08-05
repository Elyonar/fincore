package org.elyonar.fincore.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.elyonar.fincore.ledger.support.LedgerPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
@DisplayName("HTTP surface — the published contract behaves as documented")
class LedgerApiTest extends LedgerPostgresTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private UUID tenant;
    private String customer;
    private String settlement;

    @BeforeEach
    void seed() throws Exception {
        tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO currencies VALUES ('NGN',2,'Naira') ON CONFLICT (code) DO NOTHING");
        customer = openAccount("CUSTOMER", false, null);
        settlement = openAccount("SETTLEMENT_MIRROR", true, null);
    }

    private String openAccount(String type, boolean allowNegative, String groupRef) throws Exception {
        String body =
                """
                {"idempotencyKey":"%s","type":"%s","currency":"NGN","allowNegative":%s%s}
                """
                        .formatted(
                                UUID.randomUUID(),
                                type,
                                allowNegative,
                                groupRef == null ? "" : ",\"groupRef\":\"" + groupRef + "\"");
        MvcResult result =
                mvc.perform(request(post("/v1/accounts")).content(body))
                        .andExpect(status().isCreated())
                        .andReturn();
        return jsonValue(result, "accountId");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
        return builder.header(TenantResolver.TENANT_HEADER, tenant.toString()).contentType(MediaType.APPLICATION_JSON);
    }

    private static String jsonValue(MvcResult result, String field) throws Exception {
        String json = result.getResponse().getContentAsString();
        int at = json.indexOf("\"" + field + "\":\"");
        int start = at + field.length() + 4;
        return json.substring(start, json.indexOf('"', start));
    }

    private String transfer(String key, long amount) {
        return """
               {"idempotencyKey":"%s","initiatedBy":"user:ada",
                "entries":[
                  {"accountId":"%s","direction":"DEBIT","amountMinor":%d,"currency":"NGN"},
                  {"accountId":"%s","direction":"CREDIT","amountMinor":%d,"currency":"NGN"}]}
               """
                .formatted(key, settlement, amount, customer, amount);
    }

    @Test
    @DisplayName("a posting returns 201 and moves the balance")
    void posts_and_reads_back() throws Exception {
        mvc.perform(request(post("/v1/transactions")).content(transfer("tx-1", 500_00)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false));

        mvc.perform(request(get("/v1/accounts/" + customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentMinor").value("50000"))
                .andExpect(jsonPath("$.availableMinor").value("50000"));
    }

    @Test
    @DisplayName("monetary fields are decimal strings, never JSON numbers")
    void money_is_serialized_as_strings() throws Exception {
        mvc.perform(request(post("/v1/transactions")).content(transfer("tx-1", 500_00)))
                .andExpect(status().isCreated());

        // isString() is the assertion that matters: a JS consumer parsing a large balance as a
        // number would silently get a different value, with no error anywhere.
        mvc.perform(request(get("/v1/accounts/" + customer)))
                .andExpect(jsonPath("$.currentMinor").isString())
                .andExpect(jsonPath("$.holdsTotalMinor").isString())
                .andExpect(jsonPath("$.availableMinor").isString());
    }

    @Test
    @DisplayName("a replayed key returns the same transaction and moves nothing")
    void replay_is_visible_in_the_response() throws Exception {
        mvc.perform(request(post("/v1/transactions")).content(transfer("tx-dup", 500_00)))
                .andExpect(jsonPath("$.replayed").value(false));
        mvc.perform(request(post("/v1/transactions")).content(transfer("tx-dup", 500_00)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true));

        mvc.perform(request(get("/v1/accounts/" + customer)))
                .andExpect(jsonPath("$.currentMinor").value("50000"));
    }

    @Test
    @DisplayName("key reuse with a different payload is 409, not a silent replay")
    void key_reuse_is_409() throws Exception {
        mvc.perform(request(post("/v1/transactions")).content(transfer("tx-reuse", 500_00)));
        mvc.perform(request(post("/v1/transactions")).content(transfer("tx-reuse", 900_00)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                .andExpect(jsonPath("$.retryableWithSameKey").value(false));
    }

    @Test
    @DisplayName("an unbalanced posting is 422 — it can never succeed as written")
    void unbalanced_is_422() throws Exception {
        String body =
                """
                {"idempotencyKey":"tx-bad","initiatedBy":"u",
                 "entries":[
                   {"accountId":"%s","direction":"DEBIT","amountMinor":10000,"currency":"NGN"},
                   {"accountId":"%s","direction":"CREDIT","amountMinor":9000,"currency":"NGN"}]}
                """
                        .formatted(settlement, customer);
        mvc.perform(request(post("/v1/transactions")).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNBALANCED"));
    }

    @Test
    @DisplayName("a decimal amount is refused rather than rounded")
    void decimal_amounts_are_refused() throws Exception {
        String body =
                """
                {"idempotencyKey":"tx-dec","initiatedBy":"u",
                 "entries":[
                   {"accountId":"%s","direction":"DEBIT","amountMinor":100.50,"currency":"NGN"},
                   {"accountId":"%s","direction":"CREDIT","amountMinor":100.50,"currency":"NGN"}]}
                """
                        .formatted(settlement, customer);
        mvc.perform(request(post("/v1/transactions")).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("not a decimal")));
    }

    @Test
    @DisplayName("another tenant's account is 404, indistinguishable from unknown")
    void cross_tenant_reads_are_404() throws Exception {
        UUID otherTenant = UUID.randomUUID();
        mvc.perform(
                        get("/v1/accounts/" + customer)
                                .header(TenantResolver.TENANT_HEADER, otherTenant.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    @DisplayName("a missing tenant header is rejected")
    void tenant_header_is_required() throws Exception {
        mvc.perform(get("/v1/accounts/" + customer)).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("holds place, read and release through HTTP with a precise outcome")
    void hold_lifecycle_over_http() throws Exception {
        mvc.perform(request(post("/v1/transactions")).content(transfer("fund", 1_000_00)));

        String holdBody =
                """
                {"idempotencyKey":"h-1","accountId":"%s","amountMinor":30000,"currency":"NGN","expiresAt":"%s"}
                """
                        .formatted(customer, Instant.now().plus(1, ChronoUnit.DAYS));
        MvcResult placed =
                mvc.perform(request(post("/v1/holds")).content(holdBody)).andExpect(status().isCreated()).andReturn();
        String holdId = jsonValue(placed, "holdId");

        mvc.perform(request(get("/v1/accounts/" + customer)))
                .andExpect(jsonPath("$.holdsTotalMinor").value("30000"))
                .andExpect(jsonPath("$.availableMinor").value("70000"));

        mvc.perform(request(get("/v1/holds/" + holdId))).andExpect(jsonPath("$.status").value("ACTIVE"));

        mvc.perform(request(post("/v1/holds/" + holdId + "/release")))
                .andExpect(jsonPath("$.outcome").value("RELEASED_NOW"));
        mvc.perform(request(post("/v1/holds/" + holdId + "/release")))
                .andExpect(jsonPath("$.outcome").value("ALREADY_RELEASED"));
    }

    @Test
    @DisplayName("a reversal returns the winner's id when the original is already reversed")
    void reversal_conflict_carries_the_winner() throws Exception {
        MvcResult posted =
                mvc.perform(request(post("/v1/transactions")).content(transfer("tx-1", 500_00))).andReturn();
        String txId = jsonValue(posted, "transactionId");

        MvcResult reversed =
                mvc.perform(
                                request(post("/v1/transactions/" + txId + "/reverse"))
                                        .content("{\"idempotencyKey\":\"rev-1\",\"initiatedBy\":\"user:ops\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        String winner = jsonValue(reversed, "reversalTransactionId");

        mvc.perform(
                        request(post("/v1/transactions/" + txId + "/reverse"))
                                .content("{\"idempotencyKey\":\"rev-2\",\"initiatedBy\":\"user:ops\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REVERSED"))
                .andExpect(jsonPath("$.detail").value(winner));
    }

    @Test
    @DisplayName("closing requires a zero balance, then succeeds")
    void account_close_over_http() throws Exception {
        mvc.perform(request(post("/v1/transactions")).content(transfer("fund", 100_00)));

        mvc.perform(request(post("/v1/accounts/" + customer + "/close")).content("{\"closedBy\":\"user:ops\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLOSE_BLOCKED"));

        String sweep =
                """
                {"idempotencyKey":"sweep","initiatedBy":"u",
                 "entries":[
                   {"accountId":"%s","direction":"DEBIT","amountMinor":10000,"currency":"NGN"},
                   {"accountId":"%s","direction":"CREDIT","amountMinor":10000,"currency":"NGN"}]}
                """
                        .formatted(customer, settlement);
        mvc.perform(request(post("/v1/transactions")).content(sweep)).andExpect(status().isCreated());

        mvc.perform(request(post("/v1/accounts/" + customer + "/close")).content("{\"closedBy\":\"user:ops\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    @DisplayName("a group balance sums its shards and serializes as a string")
    void group_balance_sums_shards() throws Exception {
        String shardA = openAccount("FEE", true, "fees-pool");
        String shardB = openAccount("FEE", true, "fees-pool");

        for (String shard : new String[] {shardA, shardB}) {
            String body =
                    """
                    {"idempotencyKey":"%s","initiatedBy":"u",
                     "entries":[
                       {"accountId":"%s","direction":"DEBIT","amountMinor":25000,"currency":"NGN"},
                       {"accountId":"%s","direction":"CREDIT","amountMinor":25000,"currency":"NGN"}]}
                    """
                            .formatted(UUID.randomUUID(), settlement, shard);
            mvc.perform(request(post("/v1/transactions")).content(body)).andExpect(status().isCreated());
        }

        mvc.perform(request(get("/v1/account-groups/fees-pool/balance")))
                .andExpect(jsonPath("$.memberCount").value(2))
                .andExpect(jsonPath("$.currentMinor").value("50000"))
                .andExpect(jsonPath("$.currentMinor").isString());
    }

    @Test
    @DisplayName("periods close through HTTP and then reject backdated postings")
    void period_close_over_http() throws Exception {
        String end = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Lagos")).minusDays(5).toString();

        mvc.perform(request(post("/v1/periods/" + end + "/close")).content("{\"closedBy\":\"user:ops\"}"))
                .andExpect(status().isOk());

        mvc.perform(request(get("/v1/periods"))).andExpect(jsonPath("$[0].periodEnd").value(end));

        String backdated =
                """
                {"idempotencyKey":"tx-back","initiatedBy":"u","backdateReason":"late file",
                 "entries":[
                   {"accountId":"%s","direction":"DEBIT","amountMinor":10000,"currency":"NGN","valueDate":"%s"},
                   {"accountId":"%s","direction":"CREDIT","amountMinor":10000,"currency":"NGN","valueDate":"%s"}]}
                """
                        .formatted(settlement, end, customer, end);
        mvc.perform(request(post("/v1/transactions")).content(backdated))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALUE_DATE_INVALID"));
    }
}
