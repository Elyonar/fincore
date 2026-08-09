package org.elyonar.fincore.notification.internal.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document for Notification, generated from the code rather than maintained by hand.
 *
 * <p>{@code docs/api.md} remains the agreed design — the thing amendments are made against, and the
 * thing {@code ApiTest} compares the served routes against in both directions. This is its
 * executable reflection: it cannot describe an endpoint the service does not serve.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI notificationOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("fincore — Notification Service")
                        .version("v1")
                        .description(
                                """
                                The platform's first event consumer. It reads Core's business events and turns \
                                them into messages over a registry of channels. It writes no money, publishes no \
                                events, and holds no gateway credentials.

                                **There is no endpoint that sends a message.** Messages are owed by events, never \
                                requested by callers; an endpoint that injected one would be a way to send a \
                                customer anything with no domain event behind it.

                                **`GET /v1/suppressions` is the one to know.** Every consumed event ends as a \
                                message or as a suppression carrying a reason code, so "why did my customer not \
                                get this?" is a query rather than an inference from logs.

                                **Nothing reaches a customer yet.** Both senders are the development adapter: they \
                                render, mark sent, and deliver nowhere. The startup banner says so. The messaging \
                                connector is what makes them real.
                                """)
                        .license(new License().name("AGPL-3.0-only")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"))
                .components(new Components()
                        .addSecuritySchemes(
                                "bearer",
                                // Without this, Swagger UI has no Authorize button and every protected endpoint
                                // answers 401 from a page that looks like it should work.
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description(
                                                "Paste an access token. Get one with:\n\n"
                                                        + "```\ncurl -s -X POST http://localhost:8180/realms/"
                                                        + "acme-mfb/protocol/openid-connect/token \\\n"
                                                        + "  -d grant_type=password -d client_id=fincore-cli \\\n"
                                                        + "  -d username=grace -d password=password\n```\n\n"
                                                        + "`grace` holds `notifications:read`, `templates:*` and "
                                                        + "`policy:write`. Users and their grants: "
                                                        + "keycloak/README.md.")));
    }
}
