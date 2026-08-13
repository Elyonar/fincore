package org.elyonar.fincore.core.organization.api;

import java.util.List;
import java.util.UUID;

/**
 * The claim half of an assignment: pushing a principal's unit codes to wherever tokens are minted.
 *
 * <p>ADR 0012 says unit scope travels in the token and that a caller never asserts its own scope,
 * so an assignment row is only half the fact — the other half is the {@code units} claim the
 * identity provider mints. {@code PUT /v1/users/{id}/units} wrote both; assigning through the unit
 * surface wrote only the row, so a teller moved to a branch through that screen kept the old claim
 * and the two stores disagreed until somebody happened to edit them through the other one.
 *
 * <p>Declared here rather than beside the adapter because the dependency has to point this way:
 * Admin already depends on Organization, so Organization owning the port and Admin providing the
 * implementation is the only direction that does not make the two modules mutually dependent
 * (ADR 0006). A deployment with no directory to push to gets {@link #NONE} and keeps its rows.
 */
public interface UnitClaims {

    /**
     * Makes this principal's minted claim say exactly these codes.
     *
     * <p>Called after the assignment rows are already written, so the row remains the system of
     * record and this is provisioning derived from it. Principals with no directory entry — a
     * service, which authenticates by client credentials rather than by a staff record — are the
     * implementation's business to ignore.
     *
     * @param principal exactly as tokens spell it, e.g. {@code user:ada.o}
     */
    void refresh(UUID tenantId, String principal, List<String> unitCodes);

    /** For a deployment that mints no claims. Assignment still records who works where. */
    UnitClaims NONE = (tenantId, principal, unitCodes) -> {};
}
