package org.elyonar.fincore.identity.token;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * RFC 6238 time-based one-time passwords. This is a standard algorithm implemented from vetted
 * JCE primitives (HMAC-SHA1) — composition, not invention (design.md D6). No third-party TOTP
 * library is pulled in for thirty lines of HMAC.
 *
 * <p>Verification accepts the current step and one step either side, which absorbs clock skew and
 * the moment a code rolls over as a user types it — the interoperable default every authenticator
 * app assumes.
 */
@Component
public class Totp {

    private static final int STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final int WINDOW = 1; // ±1 step
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    /** A fresh 160-bit secret, Base32-encoded as authenticator apps expect. */
    public String newSecret() {
        byte[] raw = new byte[20];
        RANDOM.nextBytes(raw);
        return base32Encode(raw);
    }

    /** The {@code otpauth://} URI an authenticator app consumes as a QR code. */
    public String provisioningUri(String issuer, String account, String secret) {
        String label = enc(issuer) + ":" + enc(account);
        return "otpauth://totp/" + label
                + "?secret=" + secret
                + "&issuer=" + enc(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    /** True if {@code code} is valid for {@code secret} now, within the ± window. */
    public boolean verify(String secret, String code) {
        if (code == null || code.length() != DIGITS) {
            return false;
        }
        long step = System.currentTimeMillis() / 1000L / STEP_SECONDS;
        byte[] key = base32Decode(secret);
        for (int offset = -WINDOW; offset <= WINDOW; offset++) {
            if (constantTimeEquals(generate(key, step + offset), code)) {
                return true;
            }
        }
        return false;
    }

    /** Package-visible for the RFC 6238 test vectors. */
    String generate(byte[] key, long counter) {
        try {
            byte[] data = new byte[8];
            long value = counter;
            for (int i = 7; i >= 0; i--) {
                data[i] = (byte) (value & 0xff);
                value >>= 8;
            }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);
            int off = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[off] & 0x7f) << 24)
                    | ((hash[off + 1] & 0xff) << 16)
                    | ((hash[off + 2] & 0xff) << 8)
                    | (hash[off + 3] & 0xff);
            int otp = binary % (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", otp);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP generation failed", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                sb.append(BASE32[(buffer >> bits) & 0x1f]);
            }
        }
        if (bits > 0) {
            sb.append(BASE32[(buffer << (5 - bits)) & 0x1f]);
        }
        return sb.toString();
    }

    static byte[] base32Decode(String s) {
        String clean = s.trim().replace("=", "").toUpperCase(java.util.Locale.ROOT);
        int buffer = 0;
        int bits = 0;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (char c : clean.toCharArray()) {
            int val = indexOf(c);
            if (val < 0) {
                continue;
            }
            buffer = (buffer << 5) | val;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xff);
            }
        }
        return out.toByteArray();
    }

    private static int indexOf(char c) {
        for (int i = 0; i < BASE32.length; i++) {
            if (BASE32[i] == c) {
                return i;
            }
        }
        return -1;
    }
}
