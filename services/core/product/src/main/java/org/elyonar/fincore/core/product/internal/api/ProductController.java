package org.elyonar.fincore.core.product.internal.api;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.auth.Authorization;
import org.elyonar.fincore.core.product.internal.ProductRecords;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The product catalogue, and the act of making a version live.
 *
 * <p>Publishing is the only endpoint on this platform outside the money path that carries
 * maker-checker, and it earns it: a product version holds the fee and the limit, so whoever can
 * draft and publish alone can raise a customer's ceiling and price against it without a second
 * signature. That is a money control wearing configuration's clothes.
 *
 * <p>There is no endpoint that edits a published version. A change is a new version, so that a
 * transaction decided under version 3 stays explicable when version 4 exists.
 */
@Tag(name = "Products", description = "The product catalogue and publishing versions")
@RestController
@RequestMapping("/v1/products")
public class ProductController {

    private final ProductRecords products;

    public ProductController(ProductRecords products) {
        this.products = products;
    }

    /** Every product and its versions, live or draft. */
    @GetMapping
    public List<ProductRecords.Product> list() {
        var identity = Authorization.require("products:read");
        return products.list(identity.tenantId());
    }

    /** Creates a product with its first version, as a DRAFT. Nothing prices until it is published. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductRecords.Product create(@RequestBody CreateProduct request) {
        var identity = Authorization.require("products:create");
        return products.create(
                identity.tenantId(),
                request.code(),
                request.name(),
                request.type(),
                Authorization.initiatedBy());
    }

    /**
     * Publishes a version.
     *
     * <p>The publisher is taken from the token and never the body — an approval whose second
     * signature the caller could name is not maker-checker at all. The database refuses author ==
     * publisher regardless of what this method does.
     */
    @PostMapping("/{id}/versions/{version}/publish")
    public ProductRecords.Version publish(@PathVariable UUID id, @PathVariable int version) {
        var identity = Authorization.require("products:publish");
        return products.publish(identity.tenantId(), id, version, Authorization.initiatedBy());
    }

    /** @param type SAVINGS or CURRENT */
    public record CreateProduct(String code, String name, String type) {}
}
