package org.elyonar.fincore.ledger.invariant;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The outcome of one verification pass. */
public record InvariantReport(
        Long runId, UUID tenantId, Instant startedAt, Instant completedAt, String scope, List<Finding> findings) {

    public long violations() {
        return findings.stream().filter(f -> f.kind() == Finding.Kind.VIOLATION).count();
    }

    public long exposures() {
        return findings.stream().filter(f -> f.kind() == Finding.Kind.AUTHORIZED_EXPOSURE).count();
    }

    /** True only when nothing at all is wrong. Exposures do not make a report unhealthy. */
    public boolean clean() {
        return violations() == 0;
    }
}
