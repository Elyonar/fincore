package org.elyonar.fincore.ledger.tenant;

import java.util.UUID;
import org.elyonar.fincore.ledger.shared.ErrorCode;
import org.elyonar.fincore.ledger.shared.LedgerException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.elyonar.fincore.ledger.shared.ErrorReason;

/**
 * Answers whether a tenant exists at all.
 *
 * <p>Row-level security isolates tenants from one another; it has nothing to say about whether a
 * tenant is real. Before this existed, any UUID in a request header was a working tenant:
 * {@code TenantConfigService} returned platform defaults when no configuration row matched, so an
 * unknown id got sensible settings and an empty, functioning ledger of its own. Every isolation
 * test passed, because isolation was never the thing that was broken.
 *
 * <p>Deliberately not row-level secured: a request must be able to ask "is this tenant real?"
 * before it has a tenant context to be scoped by. The table holds a name and a status, no money and
 * no PII.
 */
@Component
public class TenantRegistry {

    private final JdbcTemplate jdbc;

    public TenantRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Throws unless the tenant is registered and active. */
    public void requireActive(UUID tenantId) {
        String status =
                jdbc.query(
                        "SELECT status FROM tenants WHERE id = ?",
                        rs -> rs.next() ? rs.getString(1) : null,
                        tenantId);

        if (status == null) {
            // Not-found rather than a distinct "unknown tenant" code: telling a caller which
            // tenant ids exist is an enumeration oracle, and a caller with no valid tenant has
            // nothing legitimate to do with the answer.
            throw LedgerException.of(ErrorCode.ACCOUNT_NOT_FOUND, ErrorReason.UNKNOWN_TENANT)
                    .message("unknown tenant");
        }
        if (!TenantStatus.ACTIVE.name().equals(status)) {
            throw LedgerException.of(ErrorCode.ACCOUNT_NOT_FOUND, ErrorReason.UNKNOWN_TENANT)
                    .message("unknown tenant");
        }
    }

    /** Registers a tenant. Provisioning only — never reachable from a request path. */
    public void register(UUID tenantId, String name, String createdBy) {
        jdbc.update(
                "INSERT INTO tenants (id, name, created_by) VALUES (?,?,?) ON CONFLICT (id) DO NOTHING",
                tenantId,
                name,
                createdBy);
    }
}
