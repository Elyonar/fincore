package org.elyonar.fincore.auth.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the shared authorization library. */
@ConfigurationProperties(prefix = "fincore.auth")
public class AuthProperties {

    /** How callers are identified. */
    public enum Mode {
        /** Verify a signed token against the issuer's published keys. The only deployable mode. */
        JWT,
        /**
         * Trust development headers. Verifies nothing. Refuses to start outside a sanctioned
         * profile — see {@code DevIdentityResolver}.
         */
        DEV
    }

    private Mode mode = Mode.JWT;

    /** Issuer URI whose published keys sign the tokens this service accepts. */
    private String issuerUri;

    /** Claim carrying the tenant. The tenant is never read from a header (ADR 0009). */
    private String tenantClaim = "tenant_id";

    /** Claim carrying the principal's name. Falls back to {@code sub} when absent. */
    private String principalClaim = "preferred_username";

    /** Claim carrying the caller's permissions, as an array of strings. */
    private String permissionsClaim = "permissions";

    /**
     * Profiles in which {@link Mode#DEV} is permitted.
     *
     * <p>Deliberately not overridable to include something like {@code prod}: the guard exists so
     * that enabling an unverified resolver takes two independent, visible mistakes rather than one
     * stray environment variable.
     */
    private String[] devProfiles = {"dev", "test", "local"};

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public String getIssuerUri() {
        return issuerUri;
    }

    public void setIssuerUri(String issuerUri) {
        this.issuerUri = issuerUri;
    }

    public String getTenantClaim() {
        return tenantClaim;
    }

    public void setTenantClaim(String tenantClaim) {
        this.tenantClaim = tenantClaim;
    }

    public String getPrincipalClaim() {
        return principalClaim;
    }

    public void setPrincipalClaim(String principalClaim) {
        this.principalClaim = principalClaim;
    }

    public String getPermissionsClaim() {
        return permissionsClaim;
    }

    public void setPermissionsClaim(String permissionsClaim) {
        this.permissionsClaim = permissionsClaim;
    }

    public String[] getDevProfiles() {
        return devProfiles;
    }

    public void setDevProfiles(String[] devProfiles) {
        this.devProfiles = devProfiles;
    }
}
