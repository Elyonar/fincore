package org.elyonar.fincore.product.internal.api;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.auth.Authorization;
import org.elyonar.fincore.product.api.ProductBeans;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What kinds of product this institution offers.
 *
 * <p>`SAVINGS` and `CURRENT` were a CHECK constraint, so a fixed deposit or a target-savings product
 * needed a migration and a release to name — a deployment to add a word. Nothing in the evaluator
 * branches on the type: it selects an icon, groups a list, and tells a customer what they are
 * opening. That makes it exactly the kind of value that belongs to the institution rather than to
 * the code.
 *
 * <p>Contrast the things that stayed constants. A fee kind, a limit type, a channel — each of those
 * is a branch in a program, and a new row would be a value the evaluator silently ignores. A
 * dropdown that offers something the engine will not honour is worse than a short dropdown: it
 * fails at transaction time instead of at configuration time.
 */
@Tag(name = "Product types", description = "The kinds of product this institution offers")
@RestController
@RequestMapping("/v1/product-types")
public class ProductTypeController {

    private final JdbcTemplate jdbc;

    public ProductTypeController(@Qualifier(ProductBeans.JDBC) JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    @Transactional(readOnly = true, transactionManager = ProductBeans.TRANSACTION_MANAGER)
    public List<ProductType> list() {
        var identity = Authorization.require("products:read");
        scopeTo(identity.tenantId());
        return jdbc.query(
                "SELECT code, name, active FROM product.product_types ORDER BY active DESC, name",
                (rs, i) ->
                        new ProductType(
                                rs.getString("code"), rs.getString("name"), rs.getBoolean("active")));
    }

    /**
     * Offers a type, or brings a withdrawn one back.
     *
     * <p>Withdrawing deactivates rather than deletes: products already created under a type keep
     * naming it, and a catalogue row pointing at a deleted type would render as nothing.
     */
    @PostMapping
    @Transactional(transactionManager = ProductBeans.TRANSACTION_MANAGER)
    public ProductType define(@RequestBody DefineType request) {
        var identity = Authorization.require("products:create");
        scopeTo(identity.tenantId());

        String code = request.code() == null ? "" : request.code().trim().toUpperCase();
        if (!code.matches("^[A-Z0-9_]{2,32}$")) {
            throw new IllegalArgumentException(
                    "code must be 2 to 32 characters of A-Z, 0-9 or underscore");
        }

        jdbc.update(
                """
                INSERT INTO product.product_types (tenant_id, code, name, active)
                VALUES (?,?,?, TRUE)
                ON CONFLICT (tenant_id, code) DO UPDATE
                   SET name = EXCLUDED.name, active = TRUE
                """,
                identity.tenantId(),
                code,
                request.name() == null || request.name().isBlank() ? code : request.name().trim());

        return new ProductType(code, request.name(), true);
    }

    private void scopeTo(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    public record ProductType(String code, String name, boolean active) {}

    /**
     * The request, which carries no {@code active}: offering a type activates it. Reusing the
     * response record meant an omitted field was a null mapped onto a primitive, which Jackson
     * refuses — a 400 naming nothing.
     */
    public record DefineType(String code, String name) {}
}
