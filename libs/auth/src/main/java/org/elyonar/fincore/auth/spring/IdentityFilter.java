package org.elyonar.fincore.auth.spring;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.elyonar.fincore.auth.IdentityContext;
import org.elyonar.fincore.auth.IdentityContextHolder;
import org.elyonar.fincore.auth.IdentityResolver;
import org.elyonar.fincore.auth.NotAuthenticatedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes the identity context for the duration of a request, and clears it afterwards.
 *
 * <p>The clearing is the part that matters. Servlet containers pool threads, so a context left
 * behind would be inherited by the next request served on that thread — the authorization twin of
 * the pooled-connection tenant bleed the ledger's {@code SET LOCAL} discipline exists to prevent.
 * {@link IdentityContextHolder#runIn} guarantees it, including on exception.
 *
 * <p>Unauthenticated requests are rejected here rather than allowed through to be caught later:
 * deny by default means an endpoint nobody remembered to annotate is closed, not open.
 */
public class IdentityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdentityFilter.class);

    private final IdentityResolver resolver;
    private final String[] openPaths;

    public IdentityFilter(IdentityResolver resolver, String[] openPaths) {
        this.resolver = resolver;
        this.openPaths = openPaths;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (isOpen(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        IdentityContext context;
        try {
            context = resolver.resolve(request);
        } catch (NotAuthenticatedException e) {
            // Logged with the reason, answered without it. A caller learning exactly why a token
            // was rejected learns how to produce one that is not.
            log.debug("rejected an unauthenticated request to {}: {}", request.getRequestURI(), e.getMessage());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        // The raw bearer rides along only when the resolver actually verified it (ADR 0014's
        // outbound propagation). A dev-mode header assertion must never be forwarded: propagation
        // would launder it into something downstream mistakes for a credential.
        String bearer = resolver.verifies() ? rawBearer(request) : null;
        try {
            org.elyonar.fincore.auth.PropagatedBearer.callWith(
                    bearer,
                    () ->
                            IdentityContextHolder.callIn(
                                    context,
                                    () -> {
                                        chain.doFilter(request, response);
                                        return null;
                                    }));
        } catch (ServletException | IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }

    private static String rawBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private boolean isOpen(String path) {
        for (String open : openPaths) {
            if (path.equals(open)) {
                return true;
            }
            if (open.endsWith("/**")) {
                // The prefix without its trailing slash, so `/actuator/health/**` admits
                // `/actuator/health` as well as everything beneath it. Matching only the
                // sub-paths made readiness answer 401 — an orchestrator would never see the
                // service as up, and the first symptom is a deployment that never goes live.
                String prefix = open.substring(0, open.length() - 3);
                if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                    return true;
                }
            }
        }
        return false;
    }
}
