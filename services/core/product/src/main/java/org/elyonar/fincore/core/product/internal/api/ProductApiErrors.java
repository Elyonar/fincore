package org.elyonar.fincore.core.product.internal.api;

import java.util.Map;
import org.elyonar.fincore.core.product.internal.ProductAuthoring;
import org.elyonar.fincore.core.product.internal.ProductRecords;
import org.elyonar.fincore.core.product.internal.RuleValidation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.elyonar.fincore.core.product.api.ProductErrorCode;

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

    @ExceptionHandler(ProductAuthoring.VersionNotDraft.class)
    public ResponseEntity<Error> notDraft() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Error(ProductErrorCode.VERSION_NOT_DRAFT.code()));
    }

    @ExceptionHandler(ProductAuthoring.LoanRulesOnNonLoanProduct.class)
    public ResponseEntity<Error> loanRulesOnNonLoan() {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new Error(ProductErrorCode.LOAN_RULES_ON_NON_LOAN_PRODUCT.code()));
    }

    @ExceptionHandler(RuleValidation.EffectiveFromInThePast.class)
    public ResponseEntity<Error> effectiveFromInThePast() {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new Error(ProductErrorCode.EFFECTIVE_FROM_IN_THE_PAST.code()));
    }

    /**
     * The one refusal here that carries a reason and details.
     *
     * <p>{@code RULES_INVALID} spans a dozen causes, so hard rule 9 requires a {@code reason} to
     * tell them apart and {@code details} holding the facts a message would interpolate. The
     * {@code message} stays developer English for a log — a service configured from Lagos and
     * Abidjan cannot write the sentence an administrator should read, and should not try.
     */
    @ExceptionHandler(RuleValidation.RulesInvalid.class)
    public ResponseEntity<DetailedError> rulesInvalid(RuleValidation.RulesInvalid e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new DetailedError(
                        ProductErrorCode.RULES_INVALID.code(), e.reason(), e.getMessage(), e.details()));
    }

    /**
     * 503, and deliberately not a refusal of the rules.
     *
     * <p>An account we could not ask about is not an account that does not exist. Refusing the
     * version here would tell an administrator their correct configuration was wrong because the
     * ledger happened to be restarting.
     */
    @ExceptionHandler(RuleValidation.LedgerUnavailable.class)
    public ResponseEntity<Error> ledgerUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new Error(ProductErrorCode.LEDGER_UNREACHABLE.code()));
    }

    public record Error(String code) {}

    public record DetailedError(String code, String reason, String message, Map<String, Object> details) {}
}
