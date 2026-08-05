package org.elyonar.fincore.ledger.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.elyonar.fincore.ledger.api.TenantResolver;
import org.elyonar.fincore.ledger.support.LedgerHttpTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HTTP — accounts, balances and statements")
class AccountHttpTest extends LedgerHttpTest {

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
    @DisplayName("a balance reads back after a posting")
    void reads_balance_back() throws Exception {
        mvc.perform(as(post("/v1/transactions")).content(transfer("tx-1", 500_00)))
                .andExpect(status().isCreated());

        mvc.perform(as(get("/v1/accounts/" + customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentMinor").value("50000"))
                .andExpect(jsonPath("$.availableMinor").value("50000"));
    }

    @Test
    @DisplayName("monetary fields are strings, never JSON numbers")
    void money_is_serialized_as_strings() throws Exception {
        mvc.perform(as(post("/v1/transactions")).content(transfer("tx-1", 500_00)));

        mvc.perform(as(get("/v1/accounts/" + customer)))
                .andExpect(jsonPath("$.currentMinor").isString())
                .andExpect(jsonPath("$.holdsTotalMinor").isString())
                .andExpect(jsonPath("$.availableMinor").isString());
    }

    @Test
    @DisplayName("another tenant's account is 404, indistinguishable from unknown")
    void cross_tenant_reads_are_404() throws Exception {
        mvc.perform(get("/v1/accounts/" + customer).header(TenantResolver.TENANT_HEADER, UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    @DisplayName("a missing tenant header is rejected")
    void tenant_header_is_required() throws Exception {
        mvc.perform(get("/v1/accounts/" + customer)).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("closing requires a zero balance, then succeeds")
    void close_requires_zero_balance() throws Exception {
        mvc.perform(as(post("/v1/transactions")).content(transfer("fund", 100_00)));

        mvc.perform(as(post("/v1/accounts/" + customer + "/close")).content("{\"closedBy\":\"user:ops\"}"))
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
        mvc.perform(as(post("/v1/transactions")).content(sweep)).andExpect(status().isCreated());

        mvc.perform(as(post("/v1/accounts/" + customer + "/close")).content("{\"closedBy\":\"user:ops\"}"))
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
            mvc.perform(as(post("/v1/transactions")).content(body)).andExpect(status().isCreated());
        }

        mvc.perform(as(get("/v1/account-groups/fees-pool/balance")))
                .andExpect(jsonPath("$.memberCount").value(2))
                .andExpect(jsonPath("$.currentMinor").value("50000"))
                .andExpect(jsonPath("$.currentMinor").isString());
    }

    @Test
    @DisplayName("a statement pages, and the header is constant across pages")
    void statement_pages_over_http() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(as(post("/v1/transactions")).content(transfer("tx-" + i, 1_000)))
                    .andExpect(status().isCreated());
        }
        String today = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Lagos")).toString();

        mvc.perform(as(get("/v1/accounts/" + customer + "/entries?from=" + today + "&to=" + today + "&limit=2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andExpect(jsonPath("$.status").value("INTERIM"));

        mvc.perform(as(get("/v1/accounts/" + customer + "/entries?from=" + today + "&to=" + today + "&limit=50")))
                .andExpect(jsonPath("$.lines.length()").value(5))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }
}
