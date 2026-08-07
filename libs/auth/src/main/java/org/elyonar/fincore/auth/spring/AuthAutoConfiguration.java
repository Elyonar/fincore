package org.elyonar.fincore.auth.spring;

import jakarta.servlet.Filter;
import org.elyonar.fincore.auth.IdentityResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
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

    /**
     * Only defined in JWT mode.
     *
     * <p>Conditional rather than returning null for the other modes: a {@code @Bean} method that
     * returns null defines no bean at all, so anything injecting a {@link JwtDecoder} fails to
     * start instead of falling back — which is exactly what happened the first time a service ran
     * in dev mode.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "fincore.auth", name = "mode", havingValue = "jwt", matchIfMissing = true)
    public JwtDecoder jwtDecoder(AuthProperties properties) {
        if (properties.getIssuerUri() == null || properties.getIssuerUri().isBlank()) {
            throw new IllegalStateException(
                    "fincore.auth.mode=jwt requires fincore.auth.issuer-uri. Refusing to start "
                            + "rather than accepting tokens nobody verified.");
        }
        // Either way the keys are fetched once and cached, so tokens are verified locally per
        // request without calling the provider — which is what makes Identity critical
        // infrastructure rather than a per-transaction dependency (PRD §6.1).
        if (properties.getJwksUri() == null || properties.getJwksUri().isBlank()) {
            // Discovery from the issuer: one URL, and the ordinary case.
            return NimbusJwtDecoder.withIssuerLocation(properties.getIssuerUri()).build();
        }

        // The key set lives somewhere this service can reach and the issuer does not name. The
        // issuer claim is still verified against issuerUri — this decides where keys come from,
        // never what is trusted, and skipping that validator would accept any token signed by
        // anyone whose keys happen to be at that URL.
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.getJwksUri()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.getIssuerUri()));
        return decoder;
    }

    @Bean
    @ConditionalOnMissingBean
    public IdentityResolver identityResolver(
            AuthProperties properties,
            Environment environment,
            ObjectProvider<JwtDecoder> jwtDecoder) {
        if (properties.getMode() == AuthProperties.Mode.DEV) {
            // Constructor refuses outside a sanctioned profile.
            return new DevIdentityResolver(
                    environment.getActiveProfiles(), properties.getDevProfiles());
        }
        JwtDecoder decoder = jwtDecoder.getIfAvailable();
        if (decoder == null) {
            throw new IllegalStateException(
                    "fincore.auth.mode=jwt but no JwtDecoder is available. Refusing to start"
                            + " rather than serving requests nobody authenticated.");
        }
        return new JwtIdentityResolver(decoder, properties);
    }

    @Bean
    public FilterRegistrationBean<Filter> identityFilter(IdentityResolver resolver, AuthProperties properties) {
        FilterRegistrationBean<Filter> registration =
                new FilterRegistrationBean<>(new IdentityFilter(resolver, properties.getOpenPaths()));
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
