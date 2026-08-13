package org.elyonar.fincore.core.admin.internal;

import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.core.organization.api.UnitClaims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Pushes unit assignments into the identity directory, so the minted {@code units} claim says what
 * the assignment rows say.
 *
 * <p>Lives in Admin because this is the module that already holds a client for the directory, and
 * implements a port Organization declares because Admin depends on Organization and not the other
 * way round (ADR 0006).
 *
 * <p>Only {@code user:} principals have anything to push. A service authenticates by client
 * credentials and has no staff record to update, which is why revoking one closes the assignment
 * row and stops — there is no claim of its own to move.
 */
@Component
public class DirectoryUnitClaims implements UnitClaims {

    private static final Logger log = LoggerFactory.getLogger(DirectoryUnitClaims.class);

    private static final String USER = "user:";

    private final IdentityDirectory directory;

    public DirectoryUnitClaims(IdentityDirectory directory) {
        this.directory = directory;
    }

    @Override
    public void refresh(UUID tenantId, String principal, List<String> unitCodes) {
        if (principal == null || !principal.startsWith(USER) || !directory.configured()) {
            return;
        }
        String username = principal.substring(USER.length());

        JsonNode user = directory.userByUsername(username);
        if (user == null || user.get("id") == null) {
            // An assignment may name a principal the directory has never heard of — nothing
            // validates the string. The row is still worth keeping as attribution, and refusing the
            // whole assignment over it would make the unit surface reject principals it accepted
            // yesterday. Logged, because a persistent one is a typo somebody should see.
            log.warn("no directory user for principal {}; unit claim not refreshed", principal);
            return;
        }
        directory.setUnits(UUID.fromString(user.get("id").asString()), unitCodes);
    }
}
