package org.elyonar.fincore.core.organization.api;

import java.util.UUID;

/**
 * The one question a neighbouring module may ask Organization: does this unit exist, live, and
 * what is it?
 *
 * <p>Modelled on {@code CustomerEligibility} — a read-only port, consulted inside the caller's own
 * transaction, writing nothing. Orchestration uses it to refuse a till whose branch does not
 * exist (ADR 0012): the till table stores the unit's code and id, but this module is the
 * authority on whether that unit is real.
 */
public interface OrganizationUnits {

    /**
     * The active unit with this code, or null when there is none — including when the code names
     * another tenant's unit or a closed one. The three cases are deliberately indistinguishable,
     * for the same reason a missing customer is: row-level security already hid the other
     * tenant's row, and distinguishing "closed" from "never existed" tells a prober more than a
     * caller needs.
     */
    Unit activeUnitByCode(UUID tenantId, String code);

    /**
     * Replaces a principal's unit assignments, returning the codes now held.
     *
     * <p>Added for admin-surface §5's {@code PUT /v1/users/{id}/units}, which ADR 0017 requires to
     * write <em>both</em> records: this module's assignment rows and the {@code units} claim the
     * directory mints from. Assignment was the documented system of record while nothing derived
     * the claim, so putting a teller in a branch had no effect on authorization — the drift the
     * ADR names as a live defect. One caller, one operation, both stores.
     *
     * <p>A code naming no active unit is refused rather than silently dropped: an administrator
     * who mistypes a branch should be told, not left believing a teller is somewhere they are not.
     *
     * @param principal exactly as tokens spell it, e.g. {@code user:ada.o}
     */
    java.util.List<String> replaceAssignments(
            UUID tenantId, String principal, java.util.List<String> unitCodes, String changedBy);

    /** The active unit codes a principal is assigned to. */
    java.util.List<String> assignmentsOf(UUID tenantId, String principal);

    /**
     * A code naming no active unit of this tenant. Declared on the port rather than beside the
     * adapter because the caller that must catch it lives outside this module, and a module's
     * {@code internal} package is not importable — the boundary that keeps Organization
     * extractable is the same one that decides where its exceptions live.
     */
    class NoSuchUnit extends RuntimeException {
        /** The offending code, so a caller can say which one without parsing a message. */
        public final String code;

        public NoSuchUnit(String code) {
            super("no active unit with code " + code);
            this.code = code;
        }
    }

    /** An active organizational unit, as a neighbour module is allowed to see it. */
    record Unit(UUID id, String code, String unitType) {}
}
