package org.elyonar.fincore.customer.internal.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.elyonar.fincore.auth.Authorization;
import org.elyonar.fincore.auth.NotAuthenticatedException;
import org.elyonar.fincore.customer.internal.TenantRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a request whose tenant this deployable has never heard of.
 *
 * <p>One check, once, before any handler — the same shape as the identity filter it runs behind,
 * and for the same reason: an endpoint nobody remembered to guard is closed rather than open.
 *
 * <p><strong>404, not 403.</strong> Telling a caller which tenant ids exist is an enumeration
 * oracle, and a caller holding no valid tenant has nothing legitimate to do with the answer. It is
 * the choice the ledger made, the one Core makes, and the one Notification makes.
 *
 * <p>Ordered after authentication because the tenant comes from the validated token and never from
 * a header — a header-supplied tenant is a caller assertion, and checking one against a registry
 * would only prove the caller can spell.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class TenantGate extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantGate.class);

    private final TenantRegistry tenants;

    public TenantGate(TenantRegistry tenants) {
        this.tenants = tenants;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        UUID tenantId;
        try {
            tenantId = Authorization.tenantId();
        } catch (NotAuthenticatedException e) {
            // An open path — health, docs. No caller, so no tenant to check.
            chain.doFilter(request, response);
            return;
        }

        if (!tenants.isActive(tenantId)) {
            log.warn("refused a request for unregistered or suspended tenant {}", tenantId);
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        chain.doFilter(request, response);
    }
}
