package org.elyonar.fincore.notification.internal;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts the destination address at rest (design D-8).
 *
 * <p>A delivery record must say where a message went — that is what makes a delivery dispute
 * answerable — and that makes this table the one place in the service holding PII. PRD §7's NDPR
 * obligations attach to it specifically: field-level encryption, no plaintext in logs, and a
 * retention schedule.
 *
 * <p>AES-GCM, so the ciphertext is authenticated as well as confidential: a tampered address should
 * fail to decrypt rather than decrypt to something else. A fresh random IV per value, prefixed to
 * the ciphertext, because reusing an IV under one key in GCM is the mistake that loses the
 * guarantee entirely.
 *
 * <p><strong>The development key announces itself.</strong> Same discipline as {@code libs/auth}'s
 * dev resolver and the logging adapters: an insecure default must be impossible to run in a
 * deployed environment by accident, and must say so loudly when it is used at all.
 */
@Component
public class AddressCipher {

    private static final Logger log = LoggerFactory.getLogger(AddressCipher.class);

    private static final String DEV_KEY = "development-key-not-for-any-deployment!!";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AddressCipher(@Value("${fincore.notification.address-key:}") String configured) {
        String material = configured == null || configured.isBlank() ? DEV_KEY : configured;
        if (material.equals(DEV_KEY)) {
            log.warn(
                    "notification: recipient addresses are encrypted with the DEVELOPMENT KEY."
                            + " Anyone with the source can read them. Set fincore.notification.address-key"
                            + " before this service holds a real customer's phone number.");
        }
        // A fixed-length key from arbitrary material. SHA-256 rather than truncation, so a short
        // configured value still produces a full-entropy key rather than a padded one.
        this.key = new SecretKeySpec(sha256(material), "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            // Never fall back to plaintext. A message not sent is recoverable; a phone number
            // written to a table in the clear because encryption failed is not.
            throw new IllegalStateException("could not encrypt a recipient address", e);
        }
    }

    public String decrypt(String stored) {
        try {
            byte[] raw = Base64.getDecoder().decode(stored);
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(raw, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(
                    cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("could not decrypt a recipient address", e);
        }
    }

    private static byte[] sha256(String material) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
