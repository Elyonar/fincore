package org.elyonar.fincore.ledger.posting;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.elyonar.fincore.ledger.support.LedgerHttpTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

@DisplayName("HTTP — posting and reversing")
class TransactionHttpTest extends LedgerHttpTest {

    private String customer;
    private String settlement;

    @BeforeEach
    void seed() throws Exception {
        seedTenant();
        customer = openAccount("CUSTOMER", false, null);
        settlement = openAccount("SETTLEMENT_MIRROR", true, null);
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
    @DisplayName("a posting returns 201 and reports whether it replayed")
    void posts_and_replays() throws Exception {
        mvc.perform(as(post("/v1/transactions")).content(transfer("tx-dup", 500_00)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false));

        mvc.perform(as(post("/v1/transactions")).content(transfer("tx-dup", 500_00)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true));

        mvc.perform(as(get("/v1/accounts/" + customer)))
                .andExpect(jsonPath("$.currentMinor").value("50000"));
    }

    @Test
    @DisplayName("key reuse with a different payload is 409, not a silent replay")
    void key_reuse_is_409() throws Exception {
        mvc.perform(as(post("/v1/transactions")).content(transfer("tx-reuse", 500_00)));
        mvc.perform(as(post("/v1/transactions")).content(transfer("tx-reuse", 900_00)))
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
        mvc.perform(as(post("/v1/transactions")).content(body))
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
        mvc.perform(as(post("/v1/transactions")).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("not a decimal")));
    }

    @Test
    @DisplayName("a malformed value date is a documented 422, never an unknown outcome")
    void malformed_value_date_is_422() throws Exception {
        // Regression: this fell through to the catch-all and answered 500 with
        // retryableWithSameKey: true — telling Orchestration to retry a caller typo, on a
        // payment, with the same key, forever.
        String body =
                """
                {"idempotencyKey":"tx-baddate","initiatedBy":"u",
                 "entries":[
                   {"accountId":"%s","direction":"DEBIT","amountMinor":10000,"currency":"NGN","valueDate":"not-a-date"},
                   {"accountId":"%s","direction":"CREDIT","amountMinor":10000,"currency":"NGN","valueDate":"not-a-date"}]}
                """
                        .formatted(settlement, customer);
        mvc.perform(as(post("/v1/transactions")).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALUE_DATE_INVALID"))
                .andExpect(jsonPath("$.reason").value("VALUE_DATE_MALFORMED"))
                .andExpect(jsonPath("$.retryableWithSameKey").value(false))
                .andExpect(jsonPath("$.details.supplied").value("not-a-date"));

        // The refusal is total: no rows, no balance movement, and the key stays free.
        mvc.perform(as(get("/v1/accounts/" + customer)))
                .andExpect(jsonPath("$.currentMinor").value("0"));
    }

    @Test
    @DisplayName("a transaction reads back with its entries")
    void reads_a_transaction_back() throws Exception {
        MvcResult posted =
                mvc.perform(as(post("/v1/transactions")).content(transfer("tx-1", 500_00))).andReturn();
        String txId = jsonValue(posted, "transactionId");

        mvc.perform(as(get("/v1/transactions/" + txId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.initiatedBy").value("user:ada"))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].amountMinor").isString());
    }

    @Test
    @DisplayName("an unknown transaction is 404")
    void unknown_transaction_is_404() throws Exception {
        mvc.perform(as(get("/v1/transactions/" + java.util.UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a second reversal is 409 carrying the winner's id")
    void reversal_conflict_carries_the_winner() throws Exception {
        MvcResult posted =
                mvc.perform(as(post("/v1/transactions")).content(transfer("tx-1", 500_00))).andReturn();
        String txId = jsonValue(posted, "transactionId");

        MvcResult reversed =
                mvc.perform(
                                as(post("/v1/transactions/" + txId + "/reverse"))
                                        .content("{\"idempotencyKey\":\"rev-1\",\"initiatedBy\":\"user:ops\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        String winner = jsonValue(reversed, "reversalTransactionId");

        mvc.perform(
                        as(post("/v1/transactions/" + txId + "/reverse"))
                                .content("{\"idempotencyKey\":\"rev-2\",\"initiatedBy\":\"user:ops\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REVERSED"))
                .andExpect(jsonPath("$.detail").value(winner));
    }

    @Test
    @DisplayName("an unknown route is 404, and must never claim the outcome is unknown")
    void unknown_route_is_404() throws Exception {
        mvc.perform(as(get("/v1/nope")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.retryableWithSameKey").value(false));
    }
}
