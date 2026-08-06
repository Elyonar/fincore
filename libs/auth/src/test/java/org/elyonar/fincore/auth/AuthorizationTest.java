package org.elyonar.fincore.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The deny-by-default guarantees, and the scoping that keeps a context inside its request. */
class AuthorizationTest {

    private static final UUID TENANT = UUID.randomUUID();

    private static IdentityContext contextWith(String... permissions) {
        return new IdentityContext(
                TENANT, "user:ada.o@branch-01", "core-orchestration", Set.of(permissions), "jti-1");
    }

    @AfterEach
    void clearAnyLeakedContext() {
        // If a test leaves one behind, the next test would inherit it — the very failure this
        // class exists to prove cannot happen.
        assertThat(IdentityContextHolder.current()).isEmpty();
    }

    @Test
    void no_context_denies_rather_than_defaulting_to_anything() {
        assertThatThrownBy(() -> Authorization.require("transfers:create"))
                .isInstanceOf(NotAuthenticatedException.class);
        assertThatThrownBy(Authorization::tenantId).isInstanceOf(NotAuthenticatedException.class);
    }

    @Test
    void a_held_permission_is_allowed() {
        IdentityContextHolder.runIn(
                contextWith("transfers:create"),
                () -> assertThat(Authorization.require("transfers:create").tenantId()).isEqualTo(TENANT));
    }

    @Test
    void a_missing_permission_is_denied_and_names_what_was_required() {
        IdentityContextHolder.runIn(
                contextWith("transfers:read"),
                () -> {
                    NotAuthorizedException denied =
                            catchThrowableOfType(
                                    NotAuthorizedException.class,
                                    () -> Authorization.require("transfers:create"));
                    assertThat(denied.required()).isEqualTo("transfers:create");
                });
    }

    @Test
    void permissions_cannot_be_widened_after_the_context_is_built() {
        Set<String> mutable = new HashSet<>(Set.of("transfers:read"));
        IdentityContext context =
                new IdentityContext(TENANT, "user:ada", null, mutable, null);

        mutable.add("transfers:create");

        assertThat(context.has("transfers:create")).isFalse();
    }

    @Test
    void the_caller_allowlist_admits_only_named_services() {
        IdentityContextHolder.runIn(
                contextWith(),
                () -> {
                    assertThat(Authorization.requireCallerAnyOf("core-orchestration")).isNotNull();
                    assertThatThrownBy(() -> Authorization.requireCallerAnyOf("reporting"))
                            .isInstanceOf(NotAuthorizedException.class);
                });
    }

    @Test
    void a_call_without_a_verified_service_identity_is_not_on_any_allowlist() {
        IdentityContext noService = new IdentityContext(TENANT, "user:ada", null, Set.of(), null);
        IdentityContextHolder.runIn(
                noService,
                () ->
                        assertThatThrownBy(() -> Authorization.requireCallerAnyOf("core-orchestration"))
                                .isInstanceOf(NotAuthorizedException.class));
    }

    @Test
    void the_context_is_cleared_even_when_the_work_throws() {
        assertThatThrownBy(
                        () ->
                                IdentityContextHolder.runIn(
                                        contextWith(),
                                        () -> {
                                            throw new IllegalStateException("boom");
                                        }))
                .isInstanceOf(IllegalStateException.class);

        // A leaked context would be inherited by the next request on this pooled thread.
        assertThat(IdentityContextHolder.current()).isEmpty();
    }

    @Test
    void nesting_restores_the_outer_context_rather_than_blanking_it() {
        IdentityContext outer = contextWith("a");
        IdentityContext inner = contextWith("b");

        IdentityContextHolder.runIn(
                outer,
                () -> {
                    IdentityContextHolder.runIn(
                            inner,
                            () -> assertThat(IdentityContextHolder.require().has("b")).isTrue());
                    assertThat(IdentityContextHolder.require().has("a")).isTrue();
                });
    }

    @Test
    void tenant_and_principal_are_mandatory() {
        assertThatThrownBy(() -> new IdentityContext(null, "user:ada", null, Set.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdentityContext(TENANT, "  ", null, Set.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
