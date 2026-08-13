package org.elyonar.fincore.product.internal.api;

import org.elyonar.fincore.product.internal.ProductRecords;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.elyonar.fincore.product.api.ProductErrorCode;

/**
 * Product's own error mapping, module-local for the same boundary reason Customer's is.
 *
 * <p>{@code PUBLISHER_IS_AUTHOR} is a 403 rather than a 422: the request is well formed and the
 * version is publishable — this particular principal simply may not be the one to do it. That
 * distinction matters to the operator reading the response, because the fix is to fetch a colleague,
 * not to correct the request.
 */
@RestControllerAdvice(assignableTypes = ProductController.class)
public class ProductApiErrors {

    @ExceptionHandler(ProductRecords.NoSuchProduct.class)
    public ResponseEntity<Error> noProduct() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Error(ProductErrorCode.PRODUCT_NOT_FOUND.code()));
    }

    @ExceptionHandler(ProductRecords.NoSuchVersion.class)
    public ResponseEntity<Error> noVersion() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Error(ProductErrorCode.PRODUCT_VERSION_NOT_FOUND.code()));
    }

    @ExceptionHandler(ProductRecords.AlreadyPublished.class)
    public ResponseEntity<Error> alreadyPublished() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Error(ProductErrorCode.VERSION_ALREADY_PUBLISHED.code()));
    }

    @ExceptionHandler(ProductRecords.ProductCodeTaken.class)
    public ResponseEntity<Error> codeTaken() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Error(ProductErrorCode.PRODUCT_CODE_TAKEN.code()));
    }

    @ExceptionHandler(ProductRecords.PublisherIsAuthor.class)
    public ResponseEntity<Error> publisherIsAuthor() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new Error(ProductErrorCode.PUBLISHER_IS_AUTHOR.code()));
    }

    /**
     * A fee rule with no fee account, caught where the checker is looking.
     *
     * <p>422 with {@code PRICING_ACCOUNT_INVALID} — the same code the authoring surface uses for a
     * wrong account, because "no account" is the degenerate case of "wrong account" and the remedy
     * is the same screen. Publish is the last gate before the money path, where this row would
     * otherwise surface one stranded transaction at a time.
     */
    @ExceptionHandler(ProductRecords.FeeRuleUnpostable.class)
    public ResponseEntity<Error> feeRuleUnpostable(ProductRecords.FeeRuleUnpostable e) {
        return ResponseEntity.unprocessableEntity().body(new Error(ProductErrorCode.PRICING_ACCOUNT_INVALID.code()));
    }

    public record Error(String code) {}
}
