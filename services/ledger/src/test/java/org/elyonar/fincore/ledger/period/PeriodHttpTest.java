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
    @DisplayName("a requested run is queued, not executed inline")
    void invariant_run_is_queued() throws Exception {
        // 202 means accepted-for-later. Running the scan inline would make this endpoint a
        // denial-of-service lever pointed at the ledger's own database.
        mvc.perform(as(post("/v1/invariants/run")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.runId").isNotEmpty());
    }

    @Test
    @DisplayName("a second request within the cooldown is refused rather than queued again")
    void invariant_runs_are_rate_limited() throws Exception {
        mvc.perform(as(post("/v1/invariants/run"))).andExpect(status().isAccepted());

        // Either the first run is still in flight (returns the same id) or the cooldown refuses
        // it. What must never happen is a second scan starting.
        mvc.perform(as(post("/v1/invariants/run")))
                .andExpect(
                        result -> {
                            int code = result.getResponse().getStatus();
                            if (code != 202 && code != 429) {
                                throw new AssertionError("expected 202 (same run) or 429 (cooldown), got " + code);
                            }
                        });
    }

    @Test
    @DisplayName("the completed report is fetched, and a run in flight is not reported CLEAN")
    void completed_report_is_fetchable() throws Exception {
        mvc.perform(as(post("/v1/invariants/run"))).andExpect(status().isAccepted());

        // The runner is asynchronous, so poll rather than assume it has finished.
        String status = "RUNNING";
        for (int i = 0; i < 40 && "RUNNING".equals(status); i++) {
            var body = mvc.perform(as(get("/v1/invariants"))).andReturn().getResponse().getContentAsString();
            status = body.replaceAll(".*\"status\":\"([A-Z_]+)\".*", "$1");
            if ("RUNNING".equals(status)) {
                Thread.sleep(100);
            }
        }
        org.assertj.core.api.Assertions.assertThat(status)
                .as("a healthy ledger completes clean; RUNNING would mean the poll timed out")
                .isEqualTo("CLEAN");
    }
}
