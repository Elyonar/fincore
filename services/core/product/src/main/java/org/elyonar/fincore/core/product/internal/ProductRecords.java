package org.elyonar.fincore.core.product.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Product's administrative writes — creating products and publishing versions.
 *
 * <p>Kept out of {@code product.api} for the same reason Customer's are: that package is the
 * contract Orchestration evaluates fees and limits through, and widening it to include
 * configuration authoring would make the money path depend on the shape of an admin console.
 *
 * <p>The rule this class exists to hold is that <strong>a published version is never edited</strong>.
 * A completed transaction has to stay explicable after the configuration moves on, so a change is a
 * new version, always. The database enforces it with a trigger; this class simply never asks.
 */
@Repository
public class ProductRecords {

    private final JdbcTemplate jdbc;

    public ProductRecords(@Qualifier("productJdbcTemplate") JdbcTemplate productJdbcTemplate) {
        this.jdbc = productJdbcTemplate;
    }

    private void scopeTo(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    /**
     * Creates a product and its first draft version.
     *
     * <p>One call, because a product with no versions can price nothing — it would be a row that
     * exists solely to be half-configured, and the next caller would have to know to finish it.
     */
    @Transactional(transactionManager = "productTransactionManager")
    public Product create(UUID tenantId, String code, String name, String type, String createdBy) {
        scopeTo(tenantId);
        try {
            UUID productId =
                    jdbc.queryForObject(
                            "INSERT INTO product.products (tenant_id, code, name, type) VALUES (?,?,?,?) RETURNING id",
                            UUID.class, tenantId, code, name, type);

            jdbc.update(
                    """
                    INSERT INTO product.product_versions
                        (tenant_id, product_id, version, status, created_by)
                    VALUES (?,?,1,'DRAFT',?)
                    """,
                    tenantId, productId, createdBy);

            return read(tenantId, productId);
        } catch (DuplicateKeyException e) {
            throw new ProductCodeTaken(code);
        } catch (DataIntegrityViolationException e) {
            // The `type` CHECK. Surfaced as a caller error rather than a 500, because it is one.
            throw new IllegalArgumentException("INVALID_PRODUCT_TYPE");
        }
    }

    /** Every product with its versions. The catalogue an administrator edits, not the one pricing reads. */
    @Transactional(readOnly = true, transactionManager = "productTransactionManager")
    public List<Product> list(UUID tenantId) {
        scopeTo(tenantId);
        return jdbc
                .query(
                        "SELECT id FROM product.products ORDER BY code",
                        (rs, row) -> rs.getObject("id", UUID.class))
                .stream()
                .map(id -> read(tenantId, id))
                .toList();
    }

    /** One product with all its versions, or null when this tenant cannot see it. */
    @Transactional(readOnly = true, transactionManager = "productTransactionManager")
    public Product read(UUID tenantId, UUID productId) {
        scopeTo(tenantId);

        Product product =
                jdbc.query(
                        "SELECT id, code, name, type, created_at FROM product.products WHERE id = ?",
                        rs ->
                                rs.next()
                                        ? new Product(
                                                rs.getObject("id", UUID.class),
                                                rs.getString("code"),
                                                rs.getString("name"),
                                                rs.getString("type"),
                                                rs.getObject("created_at", OffsetDateTime.class),
                                                List.of())
                                        : null,
                        productId);
        if (product == null) {
            return null;
        }

        List<Version> versions =
                jdbc.query(
                        """
                        SELECT version, status, effective_from, created_by, published_by
                          FROM product.product_versions
                         WHERE product_id = ?
                         ORDER BY version
                        """,
                        (rs, row) ->
                                new Version(
                                        rs.getInt("version"),
                                        rs.getString("status"),
                                        rs.getObject("effective_from", OffsetDateTime.class),
                                        rs.getString("created_by"),
                                        rs.getString("published_by")),
                        productId);

        return product.withVersions(versions);
    }

    /**
     * Publishes a draft version.
     *
     * <p>Maker-checker, enforced by the database: {@code publisher_differs_from_author} refuses the
     * update when the publisher wrote the draft. The check here exists to produce a decent error
     * rather than a constraint violation — the database remains the thing that actually holds the
     * line, because a control enforced only by the code path that happens to be in front of it is
     * one refactor from absent.
     */
    @Transactional(transactionManager = "productTransactionManager")
    public Version publish(UUID tenantId, UUID productId, int version, String publishedBy) {
        scopeTo(tenantId);

        String[] found =
                jdbc.query(
                        """
                        SELECT status, created_by FROM product.product_versions
                         WHERE product_id = ? AND version = ?
                        """,
                        rs -> rs.next() ? new String[] {rs.getString("status"), rs.getString("created_by")} : null,
                        productId, version);
        if (found == null) {
            throw new NoSuchVersion();
        }
        if ("PUBLISHED".equals(found[0])) {
            // Not idempotent-success: the caller believes they are making something live that is
            // already live, and agreeing would hide a mistaken assumption about which version is.
            throw new AlreadyPublished();
        }
        if (publishedBy.equals(found[1])) {
            throw new PublisherIsAuthor();
        }

        jdbc.update(
                """
                UPDATE product.product_versions
                   SET status = 'PUBLISHED', published_by = ?
                 WHERE product_id = ? AND version = ?
                """,
                publishedBy, productId, version);

        return jdbc.queryForObject(
                """
                SELECT version, status, effective_from, created_by, published_by
                  FROM product.product_versions WHERE product_id = ? AND version = ?
                """,
                (rs, row) ->
                        new Version(
                                rs.getInt("version"),
                                rs.getString("status"),
                                rs.getObject("effective_from", OffsetDateTime.class),
                                rs.getString("created_by"),
                                rs.getString("published_by")),
                productId, version);
    }

    public record Product(
            UUID productId,
            String code,
            String name,
            String type,
            OffsetDateTime createdAt,
            List<Version> versions) {

        Product withVersions(List<Version> versions) {
            return new Product(productId, code, name, type, createdAt, versions);
        }
    }

    public record Version(
            int version, String status, OffsetDateTime effectiveFrom, String createdBy, String publishedBy) {}

    public static class NoSuchProduct extends RuntimeException {
        public NoSuchProduct() {
            super("no such product");
        }
    }

    public static class NoSuchVersion extends RuntimeException {
        public NoSuchVersion() {
            super("no such product version");
        }
    }

    public static class AlreadyPublished extends RuntimeException {
        public AlreadyPublished() {
            super("that version is already published");
        }
    }

    public static class ProductCodeTaken extends RuntimeException {
        public ProductCodeTaken(String code) {
            super("product code already in use: " + code);
        }
    }

    public static class PublisherIsAuthor extends RuntimeException {
        public PublisherIsAuthor() {
            super("the publisher of a version may not be its author");
        }
    }
}
