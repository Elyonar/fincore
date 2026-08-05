package org.elyonar.fincore.ledger.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document, generated from the code rather than maintained by hand.
 *
 * <p>{@code docs/api.md} remains the agreed design — the thing amendments are made against. This
 * is its executable reflection: it cannot describe an endpoint the service does not serve, or miss
 * one it does, which is the failure mode of every hand-written API document eventually.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ledgerOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("fincore — Ledger Service")
                                .version("v1")
                                .description(
                                        """
                                        The single source of monetary truth. Accepts complete, balanced transactions \
                                        and records them immutably.

                                        **Money is integer minor units** (kobo, not naira). Every monetary field in a \
                                        response is a decimal *string*: balances are uncapped sums that can exceed \
                                        2^53, and a JavaScript client parsing one as a number would silently get a \
                                        different value. Requests accept a number or a string; a decimal such as \
                                        `100.50` is refused rather than rounded, because it means the caller is \
                                        thinking in naira while the field is kobo.

                                        **Idempotency is required on every creating call.** Any 4xx is terminal for \
                                        that key — mint a new one for a new logical attempt. A timeout or 5xx means \
                                        the outcome is *unknown*: retry the **same** key until you get a definitive \
                                        answer. Never mint a new key for an unknown outcome; that is how double \
                                        posts happen.

                                        **Writes are Orchestration-only** in production, enforced at the transport by \
                                        mTLS and a service-identity allowlist.
                                        """)
                                .license(new License().name("AGPL-3.0-only").url("https://www.gnu.org/licenses/agpl-3.0.txt")))
                .components(
                        new Components()
                                .addParameters(
                                        TenantResolver.TENANT_HEADER,
                                        new Parameter()
                                                .in("header")
                                                .name(TenantResolver.TENANT_HEADER)
                                                .required(true)
                                                .description(
                                                        "Tenant identity. Interim: this becomes a claim on a validated"
                                                            + " token once Identity exists, and is not authentication"
                                                            + " today.")
                                                .schema(new io.swagger.v3.oas.models.media.StringSchema().format("uuid"))));
    }
}
