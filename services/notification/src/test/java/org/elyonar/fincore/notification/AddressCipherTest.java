package org.elyonar.fincore.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.elyonar.fincore.notification.internal.AddressCipher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The double lock on the address key, tested at the constructor — the same contract Identity's
 * {@code KeyRing} holds for its signing key.
 *
 * <p>No Spring context: what is under test is precisely the refusal to construct, which a booted
 * context can only demonstrate by failing to boot. Constructing directly with a {@link
 * MockEnvironment} makes each profile × key combination one plain assertion.
 */
@DisplayName("address cipher — the committed dev key never becomes a deployment's key by accident")
class AddressCipherTest {

    private static MockEnvironment profile(String... active) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(active);
        return env;
    }

    @Test
    void outside_a_sanctioned_profile_a_missing_key_refuses_startup() {
        // The warning alone was the defect: the committed constant quietly became the production
        // key, and every stored phone number was encrypted with material anyone holding the
        // source can read.
        assertThatThrownBy(() -> new AddressCipher("", profile("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fincore.notification.address-key");
        assertThatThrownBy(() -> new AddressCipher(null, profile()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a_sanctioned_dev_profile_still_gets_the_substitute() {
        // Dev and test keep working keyless — the lock is on deployments, not on laptops.
        AddressCipher cipher = new AddressCipher("", profile("dev"));
        assertThat(cipher.decrypt(cipher.encrypt("+2348012345678"))).isEqualTo("+2348012345678");
    }

    @Test
    void a_configured_key_works_in_any_profile_and_round_trips() {
        AddressCipher cipher = new AddressCipher("a-real-key-from-the-deployment", profile("prod"));
        String stored = cipher.encrypt("ada@example.test");
        assertThat(stored).isNotEqualTo("ada@example.test");
        assertThat(cipher.decrypt(stored)).isEqualTo("ada@example.test");
    }
}
