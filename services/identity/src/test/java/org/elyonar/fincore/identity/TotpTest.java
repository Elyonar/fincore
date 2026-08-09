package org.elyonar.fincore.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.elyonar.fincore.identity.token.Totp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TOTP against the RFC 6238 published test vectors. Because the algorithm is standard, its
 * correctness is checkable against the spec's own numbers rather than against a running clock —
 * which is exactly why a banking factor uses a standard algorithm and not an invented one.
 */
@DisplayName("totp — RFC 6238 test vectors, and a live round-trip")
class TotpTest {

    private final Totp totp = new Totp();

    // The RFC 6238 appendix B seed for SHA-1: ASCII "12345678901234567890" (20 bytes).
    private static final byte[] SEED = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    @DisplayName("the 6-digit codes match the RFC at its published counters")
    void rfcVectors() {
        // T=59s  -> time-step 1        -> 8-digit 94287082 -> 6-digit 287082
        assertThat(totp.generate(SEED, 1L)).isEqualTo("287082");
        // T=1111111109s -> time-step 37037036 -> 8-digit 07081804 -> 6-digit 081804
        assertThat(totp.generate(SEED, 37037036L)).isEqualTo("081804");
        // T=2000000000s -> time-step 66666666 -> 8-digit 69279037 -> 6-digit 279037
        assertThat(totp.generate(SEED, 66666666L)).isEqualTo("279037");
    }

    @Test
    @DisplayName("a freshly generated secret verifies its own current code and rejects a wrong one")
    void liveRoundTrip() {
        String secret = totp.newSecret();
        long step = System.currentTimeMillis() / 1000L / 30L;
        String current = totp.generate(Totp.base32Decode(secret), step);
        assertThat(totp.verify(secret, current)).isTrue();
        assertThat(totp.verify(secret, "000000")).isFalse();
    }

    @Test
    @DisplayName("base32 encode/decode round-trips")
    void base32RoundTrip() {
        byte[] data = "some-20-byte-seed!!!".getBytes(StandardCharsets.US_ASCII);
        assertThat(Totp.base32Decode(Totp.base32Encode(data))).isEqualTo(data);
    }
}
