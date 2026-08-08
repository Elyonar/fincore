package org.elyonar.fincore.auth;

import java.util.UUID;

/**
 * The enforcement helpers every service imports (PRD §6.3).
 *
 * <p>These are the <em>mechanics</em> — is there a caller, does it hold this permission, is it the
 * service we accept writes from. **Domain authorization is deliberately absent**: whether an
 * approval tier covers an amount, whether maker differs from checker, whether a saga is in a state
 * that permits reversal — those live in the service that owns the rule, because only it can know
 * them. A shared library that tried to hold them would either be wrong or would drag every domain
 * into itself.
 *
 * <p>Everything here denies by default. Absent context denies; absent permission denies; an
 * unrecognised caller denies (PRD §6.5 rule 3).
 */
public final class Authorization {

    private Authorization() {}

    /**
     * Requires the current caller to hold {@code permission}.
     *
     * @throws NotAuthenticatedException when nothing is authenticated on this thread
     * @throws NotAuthorizedException when the caller lacks it
     */
    public static IdentityContext require(String permission) {
        IdentityContext context = IdentityContextHolder.require();
        if (!context.has(permission)) {
            throw new NotAuthorizedException(
                    permission, "principal " + context.principal() + " lacks " + permission);
        }
        return context;
    }

    /**
     * Requires the call to have come from one of {@code services}.
     *
     * <p>This is a service's own caller allowlist — the ledger accepts writes only from
     * {@code core-orchestration}. Enforcement sits with the owning service rather than the
     * gateway, because a gateway-only model means anything reaching the internal network reaches
     * the ledger (PRD §6.2).
     */
    public static IdentityContext requireCallerAnyOf(String... services) {
        IdentityContext context = IdentityContextHolder.require();
        for (String service : services) {
            if (context.calledBy(service)) {
                return context;
            }
        }
        throw new NotAuthorizedException(
                String.join(" or ", services),
                "caller " + context.serviceIdentity() + " is not on this endpoint's allowlist");
    }

    /** The tenant whose money this request concerns. Never a header, never a request body. */
    public static UUID tenantId() {
        return IdentityContextHolder.require().tenantId();
    }

    /**
     * The human or system job to record as {@code initiated_by}.
     *
     * <p>Paired with {@link #executedBy()} rather than collapsed, because an examiner asks who
     * authorized an action and which system performed it as two separate questions.
     */
    public static String initiatedBy() {
        return IdentityContextHolder.require().principal();
    }

    /** The service-chain identity to record as {@code executed_by}. */
    public static String executedBy() {
        return IdentityContextHolder.require().serviceIdentity();
    }

    /**
     * The organizational units the caller is assigned to, as unit codes (ADR 0012).
     *
     * <p>Asserted by the identity provider, never by the caller — the same trust rule as
     * permissions. Empty for machine identities and for tenants without organizational units; a
     * consumer that requires a unit treats an empty set as a refusal, never as "everywhere".
     */
    public static java.util.Set<String> units() {
        return IdentityContextHolder.require().units();
    }
}
