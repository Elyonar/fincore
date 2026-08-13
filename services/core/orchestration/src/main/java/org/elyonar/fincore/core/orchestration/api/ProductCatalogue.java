package org.elyonar.fincore.core.orchestration.api;

import java.util.UUID;

/**
 * The one question a composing surface may ask of the catalogue: does this code name a product?
 *
 * <p>Published for the reason {@code ProductAuthoring} is: the surface that needs the answer lives
 * in {@code app}, which composes account opening across Customer, Orchestration and Product — and
 * Customer may not ask Product itself (ADR 0006). Without this check a typo'd product code was
 * accepted at opening and surfaced only on the money path, as {@code PRODUCT_NOT_FOUND} on every
 * transaction, against a link whose {@code product_code} nothing can edit.
 *
 * <p>Deliberately not "has a published version in effect": an unpublished product is configuration
 * in progress, and publishing it fixes the account without touching the link. A code the catalogue
 * has never heard of can never be fixed, which is the difference between a pause and a bricked
 * account.
 */
public interface ProductCatalogue {

    /** Whether the tenant's catalogue holds a product with this code, in any version state. */
    boolean exists(UUID tenantId, String productCode);
}
