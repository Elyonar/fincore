package org.elyonar.fincore.core.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.elyonar.fincore.core.orchestration.api.CustomerAdministration;
import org.elyonar.fincore.core.orchestration.api.CustomerEligibility;
import org.elyonar.fincore.core.orchestration.api.EligibilityResult;
import org.elyonar.fincore.core.orchestration.api.ProductAuthoring;
import org.elyonar.fincore.core.orchestration.api.ProductCatalogue;
import org.elyonar.fincore.core.orchestration.api.ProductDecision;
import org.elyonar.fincore.core.orchestration.api.ProductDecisions;
import org.elyonar.fincore.core.orchestration.api.ProductRequest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Customer and Product, as Core's tests now see them (ADR 0020).
 *
 * <p>Before the extraction these suites configured the money path by writing rows into
 * {@code customer.customers} and {@code product.limit_rules} — which worked because Core owned
 * those schemas. It does not any more, and a Core test cannot reach across a network to two other
 * deployables without becoming an integration environment rather than a test.
 *
 * <p>So the ports are supplied directly, which is what a port is for. This is not a weakening of
 * the suites: what they were actually asserting was never "this SQL insert lands" but "given a
 * TIER_2 customer and a version with this limit, the money path does the right thing". That
 * premise is now stated in a line instead of assembled in fifteen, and the assertions are
 * unchanged.
 *
 * <p><strong>What is genuinely no longer covered here, and where it moved.</strong> The rules those
 * inserts exercised — the immutability trigger, tenant isolation on the catalogue, the evaluator's
 * own arithmetic — are database and service behaviour, and they are now tested in the services that
 * own them, against their own PostgreSQL. Asserting them here as well would be asserting a
 * reimplementation of them.
 *
 * <p>Deliberately hand-written rather than mocked. These doubles hold state across a call sequence
 * — a customer goes dormant, a limit binds on the second transfer — and a mocking framework
 * expresses that as a script the reader has to run in their head.
 */
@TestConfiguration
public class FakeServices {

    @Bean
    @Primary
    public FakeCustomers fakeCustomers() {
        return new FakeCustomers();
    }

    @Bean
    @Primary
    public FakePricing fakePricing() {
        return new FakePricing();
    }

    @Bean
    @Primary
    public FakeCatalogue fakeCatalogue() {
        return new FakeCatalogue();
    }

    /** Who exists, what tier they hold, and which accounts they hold under which product. */
    public static class FakeCustomers implements CustomerEligibility, CustomerAdministration {

        private final Map<UUID, EligibilityResult> people = new ConcurrentHashMap<>();
        /** ledgerAccountId → what is held, and by whom, in which tenant. */
        private final Map<UUID, Held> accounts = new ConcurrentHashMap<>();
        private final Map<UUID, String> externalRefs = new ConcurrentHashMap<>();

        /**
         * The tenant is part of every answer, and that is not bookkeeping.
         *
         * <p>A double that ignores the tenant is <em>more permissive than the thing it stands in
         * for</em>, and the suites using it include one whose whole job is to prove another
         * tenant's reads come back empty. Without this it passed by leaking, which is the worst
         * outcome available: a green test asserting the opposite of the truth.
         */
        private record Held(UUID tenantId, UUID customerId, String productCode, String currency, String number) {}

        /** Issued in order, ten digits, never twice — the shape Core's callers assert on. */
        private final java.util.concurrent.atomic.AtomicLong nextNumber =
                new java.util.concurrent.atomic.AtomicLong(1);

        /** A customer who may transact, at this tier. */
        public FakeCustomers eligible(UUID customerId, String kycTier) {
            people.put(customerId, EligibilityResult.eligible(kycTier));
            externalRefs.put(customerId, "%010d".formatted(nextNumber.get()));
            return this;
        }

        /** A customer who exists and may not transact — dormant, frozen, closed. */
        public FakeCustomers refused(UUID customerId, EligibilityResult.Reason reason) {
            people.put(customerId, EligibilityResult.refused(reason));
            return this;
        }

        /** This customer holds this ledger account, governed by this product. */
        public FakeCustomers holds(UUID customerId, UUID ledgerAccountId, String productCode, String currency) {
            return holds(null, customerId, ledgerAccountId, productCode, currency);
        }

        /** Scoped to a tenant. The shorter form is for suites that only ever use one. */
        public FakeCustomers holds(
                UUID tenantId, UUID customerId, UUID ledgerAccountId, String productCode, String currency) {
            accounts.put(
                    ledgerAccountId,
                    new Held(tenantId, customerId, productCode, currency,
                            "%010d".formatted(nextNumber.getAndIncrement())));
            return this;
        }

        /** Null tenant on a seeded holding means "the suite's own", so it matches whatever asks. */
        private boolean visibleIn(Held held, UUID tenantId) {
            return held != null && (held.tenantId() == null || held.tenantId().equals(tenantId));
        }

        public void clear() {
            people.clear();
            accounts.clear();
            externalRefs.clear();
        }

        @Override
        public EligibilityResult check(UUID tenantId, UUID customerId) {
            EligibilityResult known = people.get(customerId);
            return known == null ? EligibilityResult.refused(EligibilityResult.Reason.NOT_FOUND) : known;
        }

        @Override
        public boolean holdsAccount(UUID tenantId, UUID customerId, UUID ledgerAccountId) {
            Held held = accounts.get(ledgerAccountId);
            return visibleIn(held, tenantId) && held.customerId().equals(customerId);
        }

        @Override
        public String productOfHeldAccount(UUID tenantId, UUID customerId, UUID ledgerAccountId) {
            Held held = accounts.get(ledgerAccountId);
            // Null unless *this* customer, in *this* tenant, holds it — the security control the
            // money path depends on, and the reason a test can assert that naming somebody else's
            // account is refused.
            return !visibleIn(held, tenantId) || !held.customerId().equals(customerId)
                    ? null
                    : held.productCode();
        }

        @Override
        public List<HeldAccount> heldAccounts(UUID tenantId, UUID customerId) {
            List<HeldAccount> held = new ArrayList<>();
            accounts.forEach((ledgerAccountId, one) -> {
                if (visibleIn(one, tenantId) && one.customerId().equals(customerId)) {
                    held.add(new HeldAccount(
                            ledgerAccountId, one.number(), one.currency(), "PRIMARY", one.productCode()));
                }
            });
            return held;
        }

        @Override
        public java.util.Set<String> recognisedTiers(UUID tenantId) {
            // Whatever the suite has put people on. A tier nobody holds is not recognised, which is
            // what the pricing check is for.
            java.util.Set<String> codes = new java.util.LinkedHashSet<>();
            people.values().forEach(one -> {
                if (one.kycTier() != null) {
                    codes.add(one.kycTier());
                }
            });
            return codes;
        }

        @Override
        public NumberSeries numbering(UUID tenantId, String series) {
            return new NumberSeries(series, "ACC", 10, 1, "ACC0000000001");
        }

        @Override
        public NumberSeries setNumbering(
                UUID tenantId, String series, String prefix, int width, long nextValue, String updatedBy) {
            return new NumberSeries(series, prefix, width, nextValue, prefix + nextValue);
        }

        @Override
        public OpenedAccount linkWithNumber(
                UUID tenantId,
                UUID customerId,
                UUID ledgerAccountId,
                String currency,
                String role,
                String productCode,
                String accountNumber) {
            String number =
                    accountNumber == null ? "%010d".formatted(nextNumber.getAndIncrement()) : accountNumber;
            accounts.put(ledgerAccountId, new Held(tenantId, customerId, productCode, currency, number));
            return new OpenedAccount(ledgerAccountId, number, currency, role, productCode);
        }

        @Override
        public String externalRefOf(UUID tenantId, UUID customerId) {
            return externalRefs.get(customerId);
        }
    }

    /**
     * What a product decides.
     *
     * <p>Configured as an answer rather than as rules, because Core is not the thing that evaluates
     * rules any more. A test that wanted to assert the evaluator's arithmetic would be asserting
     * the Product service's behaviour from the wrong side of a network boundary; those assertions
     * live in that service's suite now.
     */
    public static class FakePricing implements ProductDecisions {

        private volatile long basisPoints;
        private volatile long flatMinor;
        private volatile Long capMinor;
        private volatile UUID feeAccountId;
        private volatile long limitMinor = Long.MAX_VALUE;
        private volatile Long dailyLimitMinor;
        private volatile ProductDecision.Refusal refusal;
        private volatile RuntimeException blowUp;

        /**
         * A percentage fee, optionally capped, against a per-transaction ceiling.
         *
         * <p>It computes rather than returning a constant, because several of Core's assertions are
         * about what it does with a fee that <em>varies</em> — that the cap binds on a large
         * transfer and not a small one, and that an amount over the ceiling is refused before the
         * ledger is called. A fixed answer would let those pass without meaning anything.
         *
         * <p>This is not a second copy of the evaluator. It knows percent, cap and ceiling because
         * that is what these suites vary; every other rule the real evaluator applies is tested in
         * the Product service, against its own database.
         */
        public FakePricing percentFee(long basisPoints, Long capMinor, UUID feeAccountId, long limitMinor) {
            this.basisPoints = basisPoints;
            this.flatMinor = 0;
            this.capMinor = capMinor;
            this.feeAccountId = feeAccountId;
            this.limitMinor = limitMinor;
            this.refusal = null;
            this.blowUp = null;
            return this;
        }

        /** Permitted, with a flat fee and this per-transaction ceiling. */
        public FakePricing permits(long feeMinor, UUID feeAccountId, long limitMinor) {
            this.basisPoints = 0;
            this.flatMinor = feeMinor;
            this.capMinor = null;
            this.feeAccountId = feeAccountId;
            this.limitMinor = limitMinor;
            this.refusal = null;
            this.blowUp = null;
            return this;
        }

        public FakePricing daily(Long dailyLimitMinor) {
            this.dailyLimitMinor = dailyLimitMinor;
            return this;
        }

        public FakePricing refuses(ProductDecision.Refusal refusal) {
            this.refusal = refusal;
            this.blowUp = null;
            return this;
        }

        /** The pricing service could not be asked. Fails closed — the transaction must be refused. */
        public FakePricing unavailable(RuntimeException cause) {
            this.blowUp = cause;
            return this;
        }

        @Override
        public ProductDecision evaluate(ProductRequest request) {
            if (blowUp != null) {
                throw blowUp;
            }
            if (refusal != null) {
                return ProductDecision.refused(refusal, 1);
            }
            if (request.amountMinor() > limitMinor) {
                return ProductDecision.refused(ProductDecision.Refusal.LIMIT_EXCEEDED, 1);
            }
            // Integer arithmetic throughout — minor units never become a double (hard rule 1).
            long fee = flatMinor + (request.amountMinor() * basisPoints) / 10_000;
            if (capMinor != null && fee > capMinor) {
                fee = capMinor;
            }
            return ProductDecision.permitted(fee, feeAccountId, limitMinor, dailyLimitMinor, 1);
        }
    }

    /** Which product codes are real. Account opening asks; the money path does not. */
    public static class FakeCatalogue implements ProductCatalogue {

        private final java.util.Set<String> known = ConcurrentHashMap.newKeySet();
        private volatile boolean acceptAll = true;

        public FakeCatalogue only(String... codes) {
            acceptAll = false;
            known.clear();
            known.addAll(List.of(codes));
            return this;
        }

        @Override
        public boolean exists(UUID tenantId, String productCode) {
            return acceptAll || known.contains(productCode);
        }
    }

    /**
     * Authoring, for the suites that only need it to not be missing.
     *
     * <p>Core keeps the pricing *controller* and its fee-account validation, so a test of that
     * validation is a test of Core. What happens after the validation passes is the Product
     * service's, and this stands in for it.
     */
    @Bean
    @Primary
    public ProductAuthoring fakeAuthoring() {
        return new ProductAuthoring() {
            private final Map<String, VersionDetail> versions = new ConcurrentHashMap<>();

            @Override
            public int draftNextVersion(UUID tenantId, UUID productId, Integer copyFrom, String author) {
                return 1;
            }

            @Override
            public void setFeeRules(UUID tenantId, UUID productId, int version, List<FeeRule> rules) {
                versions.merge(
                        productId + ":" + version,
                        detail(productId, version, rules, List.of()),
                        (was, now) -> detail(productId, version, rules, was.limitRules()));
            }

            @Override
            public void setLimitRules(UUID tenantId, UUID productId, int version, List<LimitRule> rules) {
                versions.merge(
                        productId + ":" + version,
                        detail(productId, version, List.of(), rules),
                        (was, now) -> detail(productId, version, was.feeRules(), rules));
            }

            @Override
            public void setEffectiveFrom(UUID tenantId, UUID productId, int version, String effectiveFrom) {}

            @Override
            public VersionDetail read(UUID tenantId, UUID productId, int version) {
                VersionDetail known = versions.get(productId + ":" + version);
                if (known == null) {
                    throw new NoSuchVersion();
                }
                return known;
            }

            private VersionDetail detail(
                    UUID productId, int version, List<FeeRule> fees, List<LimitRule> limits) {
                return new VersionDetail(
                        productId, "TEST", "SAVINGS", version, "DRAFT", null, null, fees, limits);
            }
        };
    }

    /** The details map a coded refusal carries, for assertions that read one. */
    public static Map<String, Object> noDetails() {
        return Map.of();
    }
}
