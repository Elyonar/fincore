package org.elyonar.fincore.core.lending.internal;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The schedule engine (lending.md §2): annuity, flat and bullet, generated once into rows.
 *
 * <p>Money is integer minor units; the intermediate arithmetic that needs fractions uses
 * {@link BigDecimal} (exact decimal, never a float — the ArchUnit rule bans binary floating
 * point, not precise arithmetic). Rounding is half-even per installment and <strong>the final
 * installment absorbs the residue</strong>, so principal components sum exactly to the principal
 * and the whole schedule is provable by addition rather than trusted.
 *
 * <p>Deterministic by construction: same inputs, byte-identical rows. The property suite sweeps
 * this rather than taking the sentence's word for it.
 */
public final class ScheduleEngine {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_EVEN);
    private static final BigDecimal TEN_K = BigDecimal.valueOf(10_000);
    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);

    private ScheduleEngine() {}

    /** One installment, in minor units. */
    public record Installment(int no, LocalDate dueDate, long principalMinor, long interestMinor) {}

    /**
     * Generates the schedule.
     *
     * @param graceMonths leading installments that carry interest only; principal amortizes over
     *     the remaining term
     */
    public static List<Installment> generate(
            String kind, long principalMinor, int rateBp, int termMonths, int graceMonths, LocalDate disbursedOn) {
        if (graceMonths >= termMonths) {
            throw new IllegalArgumentException("grace must leave at least one amortizing installment");
        }
        return switch (kind) {
            case "ANNUITY" -> annuity(principalMinor, rateBp, termMonths, graceMonths, disbursedOn);
            case "FLAT" -> flat(principalMinor, rateBp, termMonths, graceMonths, disbursedOn);
            case "BULLET" -> bullet(principalMinor, rateBp, termMonths, disbursedOn);
            default -> throw new IllegalArgumentException("unknown schedule kind " + kind);
        };
    }

    private static List<Installment> annuity(
            long principal, int rateBp, int term, int grace, LocalDate disbursedOn) {
        BigDecimal p = BigDecimal.valueOf(principal);
        BigDecimal monthlyRate =
                BigDecimal.valueOf(rateBp).divide(TEN_K, MC).divide(TWELVE, MC);
        int amortizing = term - grace;

        List<Installment> rows = new ArrayList<>(term);
        BigDecimal outstanding = p;

        // Grace: interest only, on the full principal.
        for (int i = 1; i <= grace; i++) {
            long interest = outstanding.multiply(monthlyRate, MC).setScale(0, RoundingMode.HALF_EVEN).longValueExact();
            rows.add(new Installment(i, disbursedOn.plusMonths(i), 0, interest));
        }

        if (monthlyRate.signum() == 0) {
            // Zero-rate annuity is straight-line principal.
            long even = principal / amortizing;
            long paid = 0;
            for (int i = 1; i <= amortizing; i++) {
                long slice = i == amortizing ? principal - paid : even;
                paid += slice;
                rows.add(new Installment(grace + i, disbursedOn.plusMonths(grace + i), slice, 0));
            }
            return rows;
        }

        // payment = P * i / (1 - (1+i)^-n)
        BigDecimal onePlus = BigDecimal.ONE.add(monthlyRate);
        BigDecimal factor = BigDecimal.ONE.subtract(BigDecimal.ONE.divide(onePlus.pow(amortizing, MC), MC));
        BigDecimal payment = p.multiply(monthlyRate, MC).divide(factor, MC);

        long principalPaid = 0;
        for (int i = 1; i <= amortizing; i++) {
            long interest =
                    outstanding.multiply(monthlyRate, MC).setScale(0, RoundingMode.HALF_EVEN).longValueExact();
            long principalSlice;
            if (i == amortizing) {
                // The residue lands here, by design: the schedule must sum exactly.
                principalSlice = principal - principalPaid;
            } else {
                principalSlice =
                        payment.subtract(BigDecimal.valueOf(interest)).setScale(0, RoundingMode.HALF_EVEN).longValueExact();
                principalSlice = Math.max(0, Math.min(principalSlice, principal - principalPaid));
            }
            principalPaid += principalSlice;
            outstanding = BigDecimal.valueOf(principal - principalPaid);
            rows.add(new Installment(grace + i, disbursedOn.plusMonths(grace + i), principalSlice, interest));
        }
        return rows;
    }

    private static List<Installment> flat(
            long principal, int rateBp, int term, int grace, LocalDate disbursedOn) {
        // Flat: total interest = P * annual rate * term/12, split evenly; principal straight-line
        // over the amortizing months. The residue of both lands in the final installment.
        long totalInterest =
                BigDecimal.valueOf(principal)
                        .multiply(BigDecimal.valueOf(rateBp), MC)
                        .multiply(BigDecimal.valueOf(term), MC)
                        .divide(TEN_K.multiply(TWELVE), MC)
                        .setScale(0, RoundingMode.HALF_EVEN)
                        .longValueExact();
        int amortizing = term - grace;
        long evenInterest = totalInterest / term;
        long evenPrincipal = principal / amortizing;

        List<Installment> rows = new ArrayList<>(term);
        long interestPaid = 0;
        long principalPaid = 0;
        for (int i = 1; i <= term; i++) {
            long interest = i == term ? totalInterest - interestPaid : evenInterest;
            interestPaid += interest;
            long principalSlice = 0;
            if (i > grace) {
                principalSlice = i == term ? principal - principalPaid : evenPrincipal;
                principalPaid += principalSlice;
            }
            rows.add(new Installment(i, disbursedOn.plusMonths(i), principalSlice, interest));
        }
        return rows;
    }

    private static List<Installment> bullet(long principal, int rateBp, int term, LocalDate disbursedOn) {
        LocalDate maturity = disbursedOn.plusMonths(term);
        long days = java.time.temporal.ChronoUnit.DAYS.between(disbursedOn, maturity);
        // ACT/365 fixed on the actual tenor — the same convention accrual uses.
        long interest =
                BigDecimal.valueOf(principal)
                        .multiply(BigDecimal.valueOf(rateBp), MC)
                        .multiply(BigDecimal.valueOf(days), MC)
                        .divide(TEN_K.multiply(BigDecimal.valueOf(365)), MC)
                        .setScale(0, RoundingMode.HALF_EVEN)
                        .longValueExact();
        return List.of(new Installment(1, maturity, principal, interest));
    }

    /** Total scheduled interest — the offer economics (PRD v1.9) come from here. */
    public static long totalInterest(List<Installment> schedule) {
        return schedule.stream().mapToLong(Installment::interestMinor).sum();
    }

    /**
     * Effective annual rate in basis points, from totals: what the borrower actually pays per
     * year on what they received. A disclosure figure (simple, not IRR) — the pack's rendition
     * can refine it; the facts to refine from are recorded.
     */
    public static int effectiveAnnualRateBp(long principal, long totalInterest, int termMonths) {
        if (principal == 0 || termMonths == 0) {
            return 0;
        }
        return BigDecimal.valueOf(totalInterest)
                .multiply(TEN_K, MC)
                .multiply(TWELVE, MC)
                .divide(BigDecimal.valueOf(principal).multiply(BigDecimal.valueOf(termMonths), MC), MC)
                .setScale(0, RoundingMode.HALF_EVEN)
                .intValueExact();
    }
}
