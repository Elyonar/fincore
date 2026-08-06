package org.elyonar.fincore.auth.spring;

import java.math.BigInteger;
import java.security.Principal;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Set;
import javax.security.auth.x500.X500Principal;

/**
 * A certificate stub carrying nothing but a subject.
 *
 * <p>Generating a real key pair and self-signing would test BouncyCastle rather than this library.
 * What is under test is how a subject DN becomes a service identity, so a subject is all this needs
 * to carry.
 */
final class TestCertificates {

    private TestCertificates() {}

    static X509Certificate withSubject(String distinguishedName) {
        return new StubCertificate(new X500Principal(distinguishedName));
    }

    private static final class StubCertificate extends X509Certificate {

        private final X500Principal subject;

        private StubCertificate(X500Principal subject) {
            this.subject = subject;
        }

        @Override
        public X500Principal getSubjectX500Principal() {
            return subject;
        }

        // Everything below is unused by the code under test.

        @Override
        public void checkValidity() {}

        @Override
        public void checkValidity(Date date) {}

        @Override
        public int getVersion() {
            return 3;
        }

        @Override
        public BigInteger getSerialNumber() {
            return BigInteger.ONE;
        }

        @Override
        public Principal getIssuerDN() {
            return subject;
        }

        @Override
        public Principal getSubjectDN() {
            return subject;
        }

        @Override
        public Date getNotBefore() {
            return new Date(0);
        }

        @Override
        public Date getNotAfter() {
            return new Date(Long.MAX_VALUE);
        }

        @Override
        public byte[] getTBSCertificate() {
            return new byte[0];
        }

        @Override
        public byte[] getSignature() {
            return new byte[0];
        }

        @Override
        public String getSigAlgName() {
            return "none";
        }

        @Override
        public String getSigAlgOID() {
            return "0";
        }

        @Override
        public byte[] getSigAlgParams() {
            return new byte[0];
        }

        @Override
        public boolean[] getIssuerUniqueID() {
            return new boolean[0];
        }

        @Override
        public boolean[] getSubjectUniqueID() {
            return new boolean[0];
        }

        @Override
        public boolean[] getKeyUsage() {
            return new boolean[0];
        }

        @Override
        public int getBasicConstraints() {
            return -1;
        }

        @Override
        public byte[] getEncoded() throws CertificateEncodingException {
            return new byte[0];
        }

        @Override
        public void verify(PublicKey key) {}

        @Override
        public void verify(PublicKey key, String sigProvider) {}

        @Override
        public String toString() {
            return subject.getName();
        }

        @Override
        public PublicKey getPublicKey() {
            return null;
        }

        @Override
        public boolean hasUnsupportedCriticalExtension() {
            return false;
        }

        @Override
        public Set<String> getCriticalExtensionOIDs() {
            return Set.of();
        }

        @Override
        public Set<String> getNonCriticalExtensionOIDs() {
            return Set.of();
        }

        @Override
        public byte[] getExtensionValue(String oid) {
            return new byte[0];
        }
    }
}
