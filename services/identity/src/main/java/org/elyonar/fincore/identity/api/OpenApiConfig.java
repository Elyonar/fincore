package org.elyonar.fincore.identity.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Generated from the code, never hand-maintained (service-scaffold §9). */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI identityOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("fincore identity")
                        .description(
                                "The platform's identity provider (ADR 0018): first-party,"
                                        + " client-driven authentication and token issuance."
                                        + " The agreed design is services/identity/docs/api.md;"
                                        + " this document is its executable reflection.")
                        .version("v1"));
    }
}
