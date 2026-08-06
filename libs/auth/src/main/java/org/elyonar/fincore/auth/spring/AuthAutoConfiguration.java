package org.elyonar.fincore.auth.spring;

import jakarta.servlet.Filter;
import org.elyonar.fincore.auth.IdentityResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Wires the shared authorization library into a service.
 *
 * <p>A service imports this library and gets identity resolution, context scoping and the
 * {@code require} helpers. What it does <em>not</em> get is domain authorization: which permission
 * an endpoint demands, and which services its write path accepts, are decisions only the owning
 * service can make (PRD §6.3).
 */
@AutoConfiguration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AuthAutoConfiguration.class);

    /** Paths served before a caller is known. Health and readiness must answer unauthenticated. */
    private static final String[] OPEN_PATHS = {"/actuator/health/**", "/actuator/info"};

    @Bean
    @ConditionalOnMissingBean
    public JwtDecoder jwtDecoder(AuthProperties properties) {
        if (properties.getMode() != AuthProperties.Mode.JWT) {
            return null;
        }
        if (properties.getIssuerUri() == null || properties.getIssuerUri().isBlank()) {
            throw new IllegalStateException(
                    "fincore.auth.mode=jwt requires fincore.auth.issuer-uri. Refusing to start "
                            + "rather than accepting tokens nobody verified.");
        }
        // fromIssuerLocation performs issuer discovery and pins the signing keys, so tokens are
        // verified locally per request without calling the provider.
        return NimbusJwtDecoder.withIssuerLocation(properties.getIssuerUri()).build();
    }

    @Bean
    @ConditionalOnMissingBean
    public IdentityResolver identityResolver(
            AuthProperties properties, Environment environment, JwtDecoder jwtDecoder) {
        if (properties.getMode() == AuthProperties.Mode.DEV) {
            // Constructor refuses outside a sanctioned profile.
            return new DevIdentityResolver(
                    environment.getActiveProfiles(), properties.getDevProfiles());
        }
        return new JwtIdentityResolver(jwtDecoder, properties);
    }

    @Bean
    public FilterRegistrationBean<Filter> identityFilter(IdentityResolver resolver) {
        FilterRegistrationBean<Filter> registration =
                new FilterRegistrationBean<>(new IdentityFilter(resolver, OPEN_PATHS));
        // Ahead of anything that might want the identity context.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    /**
     * Says at startup which resolver is active and whether it verifies anything.
     *
     * <p>A service running with an unverified resolver must be obvious from its logs. The
     * alternative — discovering it from behaviour — is how "we have authentication" becomes
     * something everyone assumes and nobody checked.
     */
    @Bean
    public InitializingBean authStartupBanner(IdentityResolver resolver) {
        return () -> {
            if (resolver.verifies()) {
                log.info("auth: resolver '{}' active — tokens are verified", resolver.name());
            } else {
                log.warn(
                        """

                        ============================================================
                          AUTH IS NOT VERIFYING ANYTHING — resolver '{}'
                          Identity is taken from request headers. Any caller can
                          claim any tenant and any permission.
                          Development only. Set fincore.auth.mode=jwt to verify.
                        ============================================================""",
                        resolver.name());
            }
        };
    }
}
