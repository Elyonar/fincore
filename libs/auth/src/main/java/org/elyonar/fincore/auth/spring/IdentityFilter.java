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

        try {
            IdentityContextHolder.callIn(
                    context,
                    () -> {
                        chain.doFilter(request, response);
                        return null;
                    });
        } catch (ServletException | IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }

    private boolean isOpen(String path) {
        for (String open : openPaths) {
            if (path.equals(open) || (open.endsWith("/**") && path.startsWith(open.substring(0, open.length() - 2)))) {
                return true;
            }
        }
        return false;
    }
}
