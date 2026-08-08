package org.elyonar.fincore.core.lending.internal.api;

import java.util.Map;
import org.elyonar.fincore.core.lending.api.LendingErrorCode;
import org.elyonar.fincore.core.lending.internal.LoanService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Lending's own error mapping — module-local by necessity, like every module's
 * ({@code ModuleBoundaryTest} forbids a shared advice knowing this module's internals).
 */
@RestControllerAdvice(assignableTypes = LendingController.class)
public class LendingApiErrors {

    private static final Map<LendingErrorCode, HttpStatus> STATUSES =
            Map.of(
                    LendingErrorCode.LOAN_NOT_FOUND, HttpStatus.NOT_FOUND,
                    LendingErrorCode.APPLICATION_STATE_INVALID, HttpStatus.UNPROCESSABLE_ENTITY,
                    LendingErrorCode.PRODUCT_NOT_LENDABLE, HttpStatus.UNPROCESSABLE_ENTITY,
                    LendingErrorCode.AMOUNT_OUT_OF_BOUNDS, HttpStatus.UNPROCESSABLE_ENTITY,
                    LendingErrorCode.TERM_OUT_OF_BOUNDS, HttpStatus.UNPROCESSABLE_ENTITY,
                    LendingErrorCode.APPROVAL_SEQUENCE_INVALID, HttpStatus.UNPROCESSABLE_ENTITY,
                    LendingErrorCode.OFFER_EXPIRED, HttpStatus.UNPROCESSABLE_ENTITY,
                    LendingErrorCode.REPAYMENT_EXCEEDS_PAYOFF, HttpStatus.UNPROCESSABLE_ENTITY,
                    LendingErrorCode.LOAN_NOT_ACTIVE, HttpStatus.UNPROCESSABLE_ENTITY);

    @ExceptionHandler(LoanService.NotFound.class)
    public ResponseEntity<Error> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new Error(LendingErrorCode.LOAN_NOT_FOUND.code(), null));
    }

    @ExceptionHandler(LoanService.Refused.class)
    public ResponseEntity<Error> refused(LoanService.Refused e) {
        return ResponseEntity.status(STATUSES.getOrDefault(e.code(), HttpStatus.UNPROCESSABLE_ENTITY))
                .body(new Error(e.code().code(), e.getMessage()));
    }

    /**
     * The disbursement's outcome is not known. 503, retry the same request — the saga's retry
     * rule, surfaced through the module that consumed it.
     */
    @ExceptionHandler(LoanService.PendingDisbursement.class)
    public ResponseEntity<Map<String, String>> pending(LoanService.PendingDisbursement e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("transactionId", e.sagaId().toString(), "state", "PENDING_RESOLUTION"));
    }

    public record Error(String code, String message) {}
}
