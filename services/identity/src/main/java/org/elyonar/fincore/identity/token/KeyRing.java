package org.elyonar.fincore.identity.token;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.elyonar.fincore.identity.internal.IdentityProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Key custody (design.md D13). The signing key is deployment-supplied by reference — a PEM
 * literal, a {@code file:} path, or the name of an environment variable — and is never stored in
 * the database, never logged, and never serialized anywhere but the public half into JWKS.
 *
 * <p>Absent a key, a sanctioned development profile generates an ephemeral pair and the startup
 * summary warns loudly — the same double-lock discipline as {@code libs/auth}'s dev resolver and
 * the ledger's header mode. Any other profile refuses to start: an issuer that silently invented
 * its own key would mint tokens nothing else trusts, or worse, tokens that a restart silently
 * invalidates.
 */
@Component
public class KeyRing {

    private static final List<String> DEV_PROFILES = List.of("dev", "test", "local");

    private final RSAKey active;
    private final RSAKey retiringPublic; // published for verification during rotation; never signs
    private final boolean ephemeral;

    public KeyRing(IdentityProperties properties, Environment env) throws Exception {
        String ref = properties.getSigning().getPrivateKeyPem();
        String pem = resolve(ref);
        if (pem != null && !pem.isBlank()) {
            this.active = fromPrivatePem(pem);
            this.ephemeral = false;
        } else {
            boolean sanctioned = List.of(env.getActiveProfiles()).stream().anyMatch(DEV_PROFILES::contains);
            if (!sanctioned) {
                throw new IllegalStateException(
                        "no signing key configured (fincore.identity.signing.private-key-pem) and no "
                                + "sanctioned dev profile active — an issuer must not invent its own key "
                                + "in a deployment");
            }
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            this.active = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey(pair.getPrivate())
                    .keyUse(KeyUse.SIGNATURE)
                    .keyIDFromThumbprint()
                    .build();
            this.ephemeral = true;
        }

        String retiringPem = resolve(properties.getSigning().getRetiringPublicKeyPem());
        this.retiringPublic =
                retiringPem == null || retiringPem.isBlank() ? null : fromPublicPem(retiringPem);
    }

    /** The key that signs. */
    public RSAKey active() {
        return active;
    }

    /** Every key a verifier should accept: the active one, plus the outgoing one mid-rotation. */
    public JWKSet published() {
        List<RSAKey> keys = new ArrayList<>();
        keys.add(active.toPublicJWK());
        if (retiringPublic != null) {
            keys.add(retiringPublic.toPublicJWK());
        }
        return new JWKSet(List.copyOf(keys));
    }

    public boolean ephemeral() {
        return ephemeral;
    }

    /** Literal PEM, {@code file:} path, or environment variable name. Empty stays empty. */
    private static String resolve(String ref) throws IOException {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        if (ref.contains("-----BEGIN")) {
            return ref;
        }
        if (ref.startsWith("file:")) {
            return Files.readString(Path.of(ref.substring("file:".length())));
        }
        return System.getenv(ref);
    }

    private static RSAKey fromPrivatePem(String pem) throws Exception {
        byte[] der = Base64.getMimeDecoder()
                .decode(pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", ""));
        KeyFactory kf = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey priv = (RSAPrivateCrtKey) kf.generatePrivate(new PKCS8EncodedKeySpec(der));
        RSAPublicKey pub = (RSAPublicKey)
                kf.generatePublic(new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent()));
        return new RSAKey.Builder(pub)
                .privateKey(priv)
                .keyUse(KeyUse.SIGNATURE)
                .keyIDFromThumbprint()
                .build();
    }

    private static RSAKey fromPublicPem(String pem) throws Exception {
        byte[] der = Base64.getMimeDecoder()
                .decode(pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", ""));
        RSAPublicKey pub = (RSAPublicKey)
                KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        return new RSAKey.Builder(pub).keyUse(KeyUse.SIGNATURE).keyIDFromThumbprint().build();
    }
}
