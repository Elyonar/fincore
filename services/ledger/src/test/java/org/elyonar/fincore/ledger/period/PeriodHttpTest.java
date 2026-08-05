package org.elyonar.fincore.ledger.period;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import org.elyonar.fincore.ledger.support.LedgerHttpTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HTTP — periods and invariants")
class PeriodHttpTest extends LedgerHttpTest {

    private String customer;
    private String settlement;

    @BeforeEach
    void seed() throws Exception {
        seedTenant();
        customer = openAccount("CUSTOMER", false, null);
        settlement = openAccount("SETTLEMENT_MIRROR", true, null);
    }

    @Test
    @DisplayName("closing a period then rejects postings dated inside it")
    void period_close_then_rejects_backdated_postings() throws Exception {
        String end = LocalDate.now(ZoneId.of("Africa/Lagos")).minusDays(5).toString();

        mvc.perform(as(post("/v1/periods/" + end + "/close")).content("{\"closedBy\":\"user:ops\"}"))
                .andExpect(status().isOk());

        mvc.perform(as(get("/v1/periods"))).andExpect(jsonPath("$[0].periodEnd").value(end));

        String backdated =
                """
                {"idempotencyKey":"tx-back","initiatedBy":"u","backdateReason":"late file",
                 "entries":[
                   {"accountId":"%s","direction":"DEBIT","amountMinor":10000,"currency":"NGN","valueDate":"%s"},
                   {"accountId":"%s","direction":"CREDIT","amountMinor":10000,"currency":"NGN","valueDate":"%s"}]}
                """
                        .formatted(settlement, end, customer, end);
        mvc.perform(as(post("/v1/transactions")).content(backdated))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALUE_DATE_INVALID"));
    }

    @Test
    @DisplayName("invariants run on demand and report clean on a healthy ledger")
    void invariants_run_and_report() throws Exception {
        mvc.perform(as(post("/v1/invariants/run")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CLEAN"))
                .andExpect(jsonPath("$.violations").value(0));

        mvc.perform(as(get("/v1/invariants")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLEAN"));
    }
}
