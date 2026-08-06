package org.elyonar.fincore.auth.spring;

import jakarta.servlet.http.HttpServletRequest;
import java.security.cert.X509Certificate;

/**
 * Which service placed this call, taken from the verified TLS peer certificate.
 *
 * <p>ADR 0009 makes the calling service a distinct identity from the principal, verified by mutual
 * TLS rather than asserted in a token. The distinction matters because the two answer different
 * questions — who authorized this, and which system performed it — and because a service identity
 * a caller could assert would not be an identity at all.
 *
 * <p>The certificate is read from the servlet container's standard attribute, which is populated
 * only after the container has verified the chain. A request that arrived without client
 * authentication yields null, and every allowlist check then denies, which is the correct default.
 *
 * <p>How certificates are issued, rotated and trusted belongs with the deployment, not here.
 */
final class ServiceIdentity {

    private static final String CERT_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

    private ServiceIdentity() {}

    /** The peer's common name, or null when the call carried no verified client certificate. */
    static String of(HttpServletRequest request) {
        Object attribute = request.getAttribute(CERT_ATTRIBUTE);
        if (!(attribute instanceof X509Certificate[] chain) || chain.length == 0) {
            return null;
        }
        return commonName(chain[0]);
    }

    private static String commonName(X509Certificate certificate) {
        // The subject's CN. Parsed rather than pattern-matched over the whole DN, because a CN
        // appearing inside some other attribute's value must not be mistaken for the subject's.
        String dn = certificate.getSubjectX500Principal().getName();
        for (String part : dn.split(",")) {
            String trimmed = part.trim();
            if (trimmed.regionMatches(true, 0, "CN=", 0, 3)) {
                String cn = trimmed.substring(3).trim();
                return cn.isEmpty() ? null : cn;
            }
        }
        return null;
    }
}
