package org.elyonar.fincore.identity.auth;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Password hashing and policy (design.md D6). Argon2id via the vetted encoder — parameters are
 * embedded in the encoded form, so raising them later re-hashes transparently at next login
 * rather than invalidating anyone.
 *
 * <p>The decoy is the timing control: login verifies exactly one hash per attempt whether the
 * user exists or not, so "unknown user" and "wrong password" cost the same. It is a hash of a
 * random value discarded at startup — there is no input that verifies against it.
 */
@Component
public class Passwords {

    private static final int MIN_LENGTH = 12;

    /**
     * A deliberately small, bundled worst-offenders list — offline, keeping the self-contained
     * posture (no external call at login or change time). The real control against reuse of a
     * breached password is the length floor plus lockout; this catches the indefensible.
     */
    private static final Set<String> BREACHED = Set.of(
            "password1234", "passw0rd1234", "adminadmin12", "qwerty123456", "welcome12345",
            "letmein12345", "password12345", "123456789012", "iloveyou1234", "changeme1234");

    private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private final String decoy;

    public Passwords() {
        this.decoy = encoder.encode(java.util.UUID.randomUUID().toString());
    }

    public String hash(String raw) {
        return encoder.encode(raw);
    }

    public boolean verify(String raw, String hash) {
        return encoder.matches(raw, hash);
    }

    /** Burns one verification for timing uniformity; always false. */
    public boolean verifyDecoy(String raw) {
        return encoder.matches(raw, decoy) && false;
    }

    /** Policy reasons, or empty when acceptable. Codes are the error contract's. */
    public List<String> policyViolations(String candidate, List<String> historyHashes) {
        if (candidate == null || candidate.length() < MIN_LENGTH) {
            return List.of("TOO_SHORT");
        }
        if (BREACHED.contains(candidate.toLowerCase(Locale.ROOT))) {
            return List.of("BREACHED");
        }
        for (String prior : historyHashes) {
            if (encoder.matches(candidate, prior)) {
                return List.of("REUSED");
            }
        }
        return List.of();
    }
}
