package org.elyonar.fincore.identity.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the identity service. Defaults are stated in {@code application.yml}. */
@ConfigurationProperties(prefix = "fincore.identity")
public class IdentityProperties {

    /** The issuer baked into every minted token. Verifiers match it exactly (libs/auth). */
    private String issuer = "http://localhost:8083";

    /**
     * Which tenant this instance authenticates. One instance serves one institution (ADR 0018);
     * with a single seeded tenant this stays empty and the instance resolves it, with several it
     * must be named and startup refuses the ambiguity otherwise.
     */
    private String tenantId = "";

    private int accessTokenTtlSeconds = 600;
    private int actionTokenTtlSeconds = 300;

    private Refresh refresh = new Refresh();
    private Throttle throttle = new Throttle();
    private Signing signing = new Signing();
    private Bootstrap bootstrap = new Bootstrap();

    /** Comma-separated {@code clientId=ENV_NAME} pairs; the env var holds the plaintext secret. */
    private String serviceClients = "";

    /**
     * What each service client may do inside a tenant (ADR 0019) — comma-separated
     * {@code clientId=permission|permission} pairs, the permissions separated by {@code |} because
     * a permission already contains a colon and the list already uses commas. A client named here
     * and not in {@code serviceClients} is a grant to nobody and is refused at startup; a client
     * with no entry keeps the tenantless token it has always had.
     */
    private String serviceClientGrants = "";

    public static class Refresh {
        private int absoluteHours = 12;

        public int getAbsoluteHours() {
            return absoluteHours;
        }

        public void setAbsoluteHours(int absoluteHours) {
            this.absoluteHours = absoluteHours;
        }
    }

    public static class Throttle {
        private int accountFailuresBeforeLock = 5;
        private int sourceFailuresBeforeDelay = 20;
        private int lockMinutes = 15;
        private int windowMinutes = 15;

        public int getAccountFailuresBeforeLock() {
            return accountFailuresBeforeLock;
        }

        public void setAccountFailuresBeforeLock(int v) {
            this.accountFailuresBeforeLock = v;
        }

        public int getSourceFailuresBeforeDelay() {
            return sourceFailuresBeforeDelay;
        }

        public void setSourceFailuresBeforeDelay(int v) {
            this.sourceFailuresBeforeDelay = v;
        }

        public int getLockMinutes() {
            return lockMinutes;
        }

        public void setLockMinutes(int v) {
            this.lockMinutes = v;
        }

        public int getWindowMinutes() {
            return windowMinutes;
        }

        public void setWindowMinutes(int v) {
            this.windowMinutes = v;
        }
    }

    public static class Signing {
        /** PKCS#8 PEM by reference: a literal PEM, {@code file:} path, or env var name. */
        private String privateKeyPem = "";

        /** The outgoing key during rotation: published for verification, never signs. */
        private String retiringPublicKeyPem = "";

        public String getPrivateKeyPem() {
            return privateKeyPem;
        }

        public void setPrivateKeyPem(String v) {
            this.privateKeyPem = v;
        }

        public String getRetiringPublicKeyPem() {
            return retiringPublicKeyPem;
        }

        public void setRetiringPublicKeyPem(String v) {
            this.retiringPublicKeyPem = v;
        }
    }

    public static class Bootstrap {
        /** Path to the ADR 0016 manifest. Empty seeds nothing, loudly. */
        private String manifest = "";

        /** Where generated temporary credentials are surfaced once, mode 600. */
        private String credentialsOut = "bootstrap/.seeded-credentials.txt";

        public String getManifest() {
            return manifest;
        }

        public void setManifest(String v) {
            this.manifest = v;
        }

        public String getCredentialsOut() {
            return credentialsOut;
        }

        public void setCredentialsOut(String v) {
            this.credentialsOut = v;
        }
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public int getAccessTokenTtlSeconds() {
        return accessTokenTtlSeconds;
    }

    public void setAccessTokenTtlSeconds(int v) {
        this.accessTokenTtlSeconds = v;
    }

    public int getActionTokenTtlSeconds() {
        return actionTokenTtlSeconds;
    }

    public void setActionTokenTtlSeconds(int v) {
        this.actionTokenTtlSeconds = v;
    }

    public Refresh getRefresh() {
        return refresh;
    }

    public void setRefresh(Refresh refresh) {
        this.refresh = refresh;
    }

    public Throttle getThrottle() {
        return throttle;
    }

    public void setThrottle(Throttle throttle) {
        this.throttle = throttle;
    }

    public Signing getSigning() {
        return signing;
    }

    public void setSigning(Signing signing) {
        this.signing = signing;
    }

    public Bootstrap getBootstrap() {
        return bootstrap;
    }

    public void setBootstrap(Bootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    public String getServiceClients() {
        return serviceClients;
    }

    public void setServiceClients(String serviceClients) {
        this.serviceClients = serviceClients;
    }

    public String getServiceClientGrants() {
        return serviceClientGrants;
    }

    public void setServiceClientGrants(String serviceClientGrants) {
        this.serviceClientGrants = serviceClientGrants;
    }
}
