package org.elyonar.fincore.core.orchestration.internal.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.elyonar.fincore.core.orchestration.api.CoreException;
import org.elyonar.fincore.core.orchestration.api.DetailKey;
import org.elyonar.fincore.core.orchestration.api.ErrorCode;
import org.elyonar.fincore.core.orchestration.api.ErrorReason;

/**
 * The property the Ledger's retry rule depends on: the same saga step always derives the same key.
 *
 * <p>If this ever became time- or attempt-dependent, a retry after an unknown outcome would post a
 * second transaction under a fresh key, and the Ledger's idempotency registry could not recognise
 * it. The double post would be produced by the mechanism written to prevent it — which is why this
 * is tested rather than assumed.
 */
class IdempotencyKeysTest {

    @Test
    void the_same_saga_step_always_derives_the_same_key() {
        UUID sagaId = UUID.randomUUID();

        String first = IdempotencyKeys.forStep(sagaId, "post");
        String second = IdempotencyKeys.forStep(sagaId, "post");

        assertThat(first).isEqualTo(second);
    }

    @Test
    void the_key_survives_a_restart_because_it_is_derived_rather_than_stored() {
        // Nothing here reads state. A worker that crashes mid-call and a different instance that
        // picks the saga up later reconstruct the identical key from the saga id alone.
        UUID sagaId = UUID.fromString("6f1b3c9e-2f4a-4a1b-9c7d-0e2f5a8b1c34");

        assertThat(IdempotencyKeys.forStep(sagaId, "post"))
                .isEqualTo("core:6f1b3c9e-2f4a-4a1b-9c7d-0e2f5a8b1c34:post");
    }

    @Test
    void different_steps_of_one_saga_get_different_keys() {
        // Otherwise a saga's second outbound call would replay the first's result.
        UUID sagaId = UUID.randomUUID();

        assertThat(IdempotencyKeys.forStep(sagaId, "post"))
                .isNotEqualTo(IdempotencyKeys.forStep(sagaId, "reverse"));
    }

    @Test
    void different_sagas_get_different_keys() {
        assertThat(IdempotencyKeys.forStep(UUID.randomUUID(), "post"))
                .isNotEqualTo(IdempotencyKeys.forStep(UUID.randomUUID(), "post"));
    }

    @Test
    void the_key_fits_the_ledgers_two_hundred_character_cap() {
        assertThat(IdempotencyKeys.forStep(UUID.randomUUID(), "post")).hasSizeLessThanOrEqualTo(200);
    }

    @Test
    void a_step_containing_the_separator_is_refused() {
        // Otherwise ("a", "b:c") and ("a:b", "c") would derive one key for two different steps.
        // Asserts the contract, not just that something was thrown: a caller has to be able to
        // tell these apart from a missing field to say anything useful to a user.
        assertThatThrownBy(() -> IdempotencyKeys.forStep(UUID.randomUUID(), "post:retry"))
                .isInstanceOf(CoreException.class)
                .extracting(e -> ((CoreException) e).errorCode(), e -> ((CoreException) e).reason())
                .containsExactly(ErrorCode.COMMAND_INVALID, ErrorReason.STEP_CONTAINS_SEPARATOR);
    }

    @Test
    void a_missing_saga_or_step_is_refused_rather_than_defaulted() {
        assertThatThrownBy(() -> IdempotencyKeys.forStep(null, "post"))
                .isInstanceOf(CoreException.class)
                .extracting(e -> ((CoreException) e).details().get(DetailKey.FIELD))
                .isEqualTo("sagaId");
        assertThatThrownBy(() -> IdempotencyKeys.forStep(UUID.randomUUID(), " "))
                .isInstanceOf(CoreException.class)
                .extracting(e -> ((CoreException) e).details().get(DetailKey.FIELD))
                .isEqualTo("step");
    }
}
