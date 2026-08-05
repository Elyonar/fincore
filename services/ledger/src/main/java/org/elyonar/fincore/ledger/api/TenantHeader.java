package org.elyonar.fincore.ledger.api;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Documents the tenant header on an endpoint, so Swagger UI offers a field for it.
 *
 * <p>Every endpoint needs it, so it is one annotation rather than fifteen copies of the same
 * parameter description that would drift apart.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Parameter(
        in = ParameterIn.HEADER,
        name = TenantResolver.TENANT_HEADER,
        required = true,
        description = "Tenant identity (UUID). Becomes a token claim once Identity exists.")
public @interface TenantHeader {}
