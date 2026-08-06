package org.elyonar.fincore.ledger.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;
import org.elyonar.fincore.ledger.api.TenantResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Base for tests that exercise the published HTTP contract.
 *
 * <p>Each slice tests its own endpoints, mirroring how the controllers are packaged, so a change
 * to holds touches one directory on both sides of the tree. This holds only what all of them need:
 * a tenant, the header every request carries, and account setup.
 */
@AutoConfigureMockMvc
public abstract class LedgerHttpTest extends LedgerPostgresTest {

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;

    protected UUID tenant;

    protected void seedTenant() {
        tenant = UUID.randomUUID();
        // Provisioning, as the seed script does it: a tenant exists before it can hold money.
        jdbc.update(
                "INSERT INTO tenants (id, name, created_by) VALUES (?, 'http test tenant', 'test')"
                        + " ON CONFLICT (id) DO NOTHING",
                tenant);
        jdbc.update("INSERT INTO currencies VALUES ('NGN',2,'Naira') ON CONFLICT (code) DO NOTHING");
    }

    /** Adds the tenant header and JSON content type to any request. */
    protected MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder) {
        return builder.header(TenantResolver.TENANT_HEADER, tenant.toString())
                .contentType(MediaType.APPLICATION_JSON);
    }

    protected String openAccount(String type, boolean allowNegative, String groupRef) throws Exception {
        String body =
                """
                {"idempotencyKey":"%s","type":"%s","currency":"NGN","allowNegative":%s%s}
                """
                        .formatted(
                                UUID.randomUUID(),
                                type,
                                allowNegative,
                                groupRef == null ? "" : ",\"groupRef\":\"" + groupRef + "\"");
        return jsonValue(mvc.perform(as(post("/v1/accounts")).content(body)).andReturn(), "accountId");
    }

    /** Pulls a top-level string field out of a JSON response body. */
    protected static String jsonValue(MvcResult result, String field) throws Exception {
        String json = result.getResponse().getContentAsString();
        int at = json.indexOf("\"" + field + "\":\"");
        int start = at + field.length() + 4;
        return json.substring(start, json.indexOf('"', start));
    }
}
