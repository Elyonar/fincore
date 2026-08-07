package org.elyonar.fincore.notification.internal.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.elyonar.fincore.auth.Authorization;
import org.elyonar.fincore.auth.NotAuthenticatedException;
import org.elyonar.fincore.notification.internal.TenantRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a request whose tenant this service has never heard of.
 *
 * <p>One check, once, before any handler — the same shape as Core's, deliberately. 404 rather than
 * 403, because telling a caller which tenants exist is an enumeration oracle and a caller with no
 * valid tenant has nothing legitimate to do with the answer.
 *
 * <p>The event path is guarded separately, in the intake: an event carries its tenant in the
 * envelope rather than in a token, so a filter cannot see it.
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
            // An open path — health, docs, the service identity. No caller, so no tenant to check.
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
