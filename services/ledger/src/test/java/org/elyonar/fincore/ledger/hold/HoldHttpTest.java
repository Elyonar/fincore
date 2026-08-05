package org.elyonar.fincore.ledger.hold;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.elyonar.fincore.ledger.support.LedgerHttpTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

@DisplayName("HTTP — holds")
class HoldHttpTest extends LedgerHttpTest {

    private String customer;
    private String settlement;

    @BeforeEach
    void seed() throws Exception {
        seedTenant();
        customer = openAccount("CUSTOMER", false, null);
        settlement = openAccount("SETTLEMENT_MIRROR", true, null);
        String fund =
                """
                {"idempotencyKey":"fund","initiatedBy":"u",
                 "entries":[
                   {"accountId":"%s","direction":"DEBIT","amountMinor":100000,"currency":"NGN"},
                   {"accountId":"%s","direction":"CREDIT","amountMinor":100000,"currency":"NGN"}]}
                """
                        .formatted(settlement, customer);
        mvc.perform(as(post("/v1/transactions")).content(fund)).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("place, read, release — with the outcome named precisely")
    void hold_lifecycle() throws Exception {
        String body =
                """
                {"idempotencyKey":"h-1","accountId":"%s","amountMinor":30000,"currency":"NGN","expiresAt":"%s"}
                """
                        .formatted(customer, Instant.now().plus(1, ChronoUnit.DAYS));

        MvcResult placed =
                mvc.perform(as(post("/v1/holds")).content(body)).andExpect(status().isCreated()).andReturn();
        String holdId = jsonValue(placed, "holdId");

        mvc.perform(as(get("/v1/accounts/" + customer)))
                .andExpect(jsonPath("$.holdsTotalMinor").value("30000"))
                .andExpect(jsonPath("$.availableMinor").value("70000"));

        mvc.perform(as(get("/v1/holds/" + holdId))).andExpect(jsonPath("$.status").value("ACTIVE"));

        mvc.perform(as(post("/v1/holds/" + holdId + "/release")))
                .andExpect(jsonPath("$.outcome").value("RELEASED_NOW"));
        mvc.perform(as(post("/v1/holds/" + holdId + "/release")))
                .andExpect(jsonPath("$.outcome").value("ALREADY_RELEASED"));
    }

    @Test
    @DisplayName("holds on an account are listable")
    void holds_are_listable() throws Exception {
        String body =
                """
                {"idempotencyKey":"h-1","accountId":"%s","amountMinor":10000,"currency":"NGN","expiresAt":"%s"}
                """
                        .formatted(customer, Instant.now().plus(1, ChronoUnit.DAYS));
        mvc.perform(as(post("/v1/holds")).content(body)).andExpect(status().isCreated());

        mvc.perform(as(get("/v1/accounts/" + customer + "/holds?status=ACTIVE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].amountMinor").isString());
    }
}
