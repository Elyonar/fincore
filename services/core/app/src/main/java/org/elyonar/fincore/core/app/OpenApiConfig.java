package org.elyonar.fincore.core.app;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document for Core, generated from the code rather than maintained by hand.
 *
 * <p>{@code docs/api.md} remains the agreed design — the thing amendments are made against. This is
 * its executable reflection: it cannot describe an endpoint the service does not serve, or miss one
 * it does.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI coreOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("fincore — Core Service")
                                .version("v1")
                                .description(
                                        """
                                        Customer, Product and Transaction Orchestration in one deployable. Turns a \
                                        business intent into a balanced, attributed, idempotent posting against the \
                                        Ledger, and guarantees a request interrupted at any point ends either \
                                        completely done or completely undone.

                                        **Idempotency is required on every creating call.** Any 4xx is terminal for \
                                        that key — mint a new one for a new logical attempt.

                                        **A 503 means the outcome is unknown, not that it failed.** Core genuinely \
                                        does not know whether the money moved, so you must retry the *same* \
                                        idempotency key until you receive a definitive answer. Never mint a new key \
                                        for an unknown outcome: that is how double payments are created, and no \
                                        downstream control can catch it. The response carries a `transactionId` you \
                                        can poll with `GET /v1/transactions/{id}`, which never mutates anything.

                                        **Money is integer minor units** (kobo, not naira), serialized as decimal \
                                        strings wherever it leaves the service.

                                        **Development identity.** When `fincore.auth.mode=dev` this service reads \
                                        `X-Dev-Tenant-Id`, `X-Dev-Principal` and `X-Dev-Permissions` headers and \
                                        verifies nothing at all. The startup banner says so loudly. In any deployed \
                                        environment the mode is `jwt` and these headers are ignored.
                                        """)
                                .license(new License().name("AGPL-3.0-only")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "bearer",
                                        // Without this, Swagger UI has no Authorize button and every protected
                                        // endpoint answers 401 from a page that looks like it should work. The
                                        // token comes from Keycloak — see keycloak/README.md.
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")
                                                .description(
                                                        "Paste an access token. Get one with:\n\n"
                                                            + "```\ncurl -s -X POST http://localhost:8180/realms/"
                                                            + "acme-mfb/protocol/openid-connect/token \\\n"
                                                            + "  -d grant_type=password -d client_id=fincore-cli"
                                                            + " \\\n  -d username=ada -d password=password\n```\n\n"
                                                            + "Users and what each may do: keycloak/README.md."))
                                .addParameters(
                                        "DevTenant",
                                        new Parameter()
                                                .in("header")
                                                .name("X-Dev-Tenant-Id")
                                                .description("Development mode only. Ignored when tokens are verified.")
                                                .required(false)));
    }
}
