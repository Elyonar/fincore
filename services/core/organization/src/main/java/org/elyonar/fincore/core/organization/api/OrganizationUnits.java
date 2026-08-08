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

    /** An active organizational unit, as a neighbour module is allowed to see it. */
    record Unit(UUID id, String code, String unitType) {}
}
