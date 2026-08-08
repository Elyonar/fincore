package org.elyonar.fincore.core.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Random;
import org.elyonar.fincore.core.lending.internal.ScheduleEngine;
import org.junit.jupiter.api.Test;

/**
 * The schedule properties (lending.md §5) — the sums are proven, never trusted.
 *
 * <p>A seeded sweep stands in for a property framework: 500 deterministic cases per kind, every
 * one asserting the invariants that make a schedule bankable. Deterministic seed, so a failure
 * reproduces exactly.
 */
class ScheduleEngineTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 8);

    private static void assertBankable(List<ScheduleEngine.Installment> rows, long principal, int term) {
        assertThat(rows).hasSize(term);
        // Principal components sum exactly to the principal — the residue rule at work.
        assertThat(rows.stream().mapToLong(ScheduleEngine.Installment::principalMinor).sum())
                .isEqualTo(principal);
        // No negative component, ever.
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.principalMinor()).isGreaterThanOrEqualTo(0);
            assertThat(r.interestMinor()).isGreaterThanOrEqualTo(0);
        });
        // Dates are strictly monotone.
        for (int i = 1; i < rows.size(); i++) {
            assertThat(rows.get(i).dueDate()).isAfter(rows.get(i - 1).dueDate());
        }
    }

    @Test
    void a_seeded_sweep_of_annuity_and_flat_schedules_always_sums_exactly() {
        Random random = new Random(20260808L);
        for (int i = 0; i < 500; i++) {
            long principal = 1_000L + (long) (random.nextDouble() * 500_000_000L);
            int rateBp = random.nextInt(6001);
            int term = 1 + random.nextInt(60);
            int grace = term > 1 ? random.nextInt(term) : 0;
            String kind = random.nextBoolean() ? "ANNUITY" : "FLAT";

            List<ScheduleEngine.Installment> rows =
                    ScheduleEngine.generate(kind, principal, rateBp, term, grace, DAY);
            assertBankable(rows, principal, term);
            // Grace installments carry no principal.
            rows.stream().limit(grace).forEach(r -> assertThat(r.principalMinor()).isZero());
        }
    }

    @Test
    void an_annuity_keeps_installments_level_except_the_residue() {
        List<ScheduleEngine.Installment> rows =
                ScheduleEngine.generate("ANNUITY", 1_200_000, 2_400, 12, 0, DAY);
        assertBankable(rows, 1_200_000, 12);
        // Level payment: every non-final installment's total is within a minor unit of the first.
        long first = rows.getFirst().principalMinor() + rows.getFirst().interestMinor();
        for (int i = 1; i < rows.size() - 1; i++) {
            long total = rows.get(i).principalMinor() + rows.get(i).interestMinor();
            assertThat(Math.abs(total - first)).isLessThanOrEqualTo(1);
        }
        // Interest declines as principal amortizes.
        assertThat(rows.getFirst().interestMinor()).isGreaterThan(rows.get(10).interestMinor());
    }

    @Test
    void flat_charges_the_stated_total_and_straight_lines_the_principal() {
        // ₦10,000.00 at 24% for 12 months: flat interest = 10000 * 24% = ₦2,400.00.
        List<ScheduleEngine.Installment> rows =
                ScheduleEngine.generate("FLAT", 1_000_000, 2_400, 12, 0, DAY);
        assertBankable(rows, 1_000_000, 12);
        assertThat(ScheduleEngine.totalInterest(rows)).isEqualTo(240_000);
        assertThat(rows.getFirst().principalMinor()).isEqualTo(1_000_000 / 12);
    }

    @Test
    void bullet_is_one_installment_with_act_365_interest_on_the_actual_tenor() {
        List<ScheduleEngine.Installment> rows =
                ScheduleEngine.generate("BULLET", 1_000_000, 3_650, 12, 0, DAY);
        assertThat(rows).hasSize(1);
        long days = java.time.temporal.ChronoUnit.DAYS.between(DAY, DAY.plusMonths(12));
        // 36.50% ACT/365: interest = P * days/1000 exactly at this rate.
        assertThat(rows.getFirst().interestMinor()).isEqualTo(1_000_000L * days / 1000);
        assertThat(rows.getFirst().principalMinor()).isEqualTo(1_000_000);
    }

    @Test
    void zero_rate_schedules_are_pure_principal() {
        for (String kind : new String[] {"ANNUITY", "FLAT"}) {
            List<ScheduleEngine.Installment> rows = ScheduleEngine.generate(kind, 999_999, 0, 7, 0, DAY);
            assertBankable(rows, 999_999, 7);
            assertThat(ScheduleEngine.totalInterest(rows)).isZero();
        }
    }

    @Test
    void the_offer_economics_come_from_the_schedule() {
        List<ScheduleEngine.Installment> rows =
                ScheduleEngine.generate("FLAT", 1_000_000, 2_400, 12, 0, DAY);
        long interest = ScheduleEngine.totalInterest(rows);
        // Flat 24% over 12 months reads back as 24% effective-simple on this definition.
        assertThat(ScheduleEngine.effectiveAnnualRateBp(1_000_000, interest, 12)).isEqualTo(2_400);
    }
}
