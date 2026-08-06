package org.elyonar.fincore.core.customer.internal.api;

import org.elyonar.fincore.core.customer.internal.CustomerRecords;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Customer's own error mapping.
 *
 * <p>Module-local by necessity, not preference: Orchestration's {@code ApiErrors} cannot handle
 * these, because handling them would mean importing {@code customer.internal}, which
 * {@code ModuleBoundaryTest} forbids. The boundary that keeps Customer extractable is the same one
 * that stops a single shared advice from knowing about it, and one advice per module is the price.
 * Authentication and authorization failures are not repeated here — those come from {@code
 * libs/auth}, which every module may depend on, and are already mapped once, globally.
 */
@RestControllerAdvice(assignableTypes = CustomerController.class)
public class CustomerApiErrors {

    /**
     * Absent, or another tenant's. One status for both.
     *
     * <p>A 404 that distinguished the two would confirm that a customer exists somewhere — which is
     * exactly what row-level security spent the query hiding.
     */
    @ExceptionHandler(CustomerRecords.NoSuchCustomer.class)
    public ResponseEntity<Error> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Error("CUSTOMER_NOT_FOUND"));
    }

    @ExceptionHandler(CustomerRecords.ExternalRefTaken.class)
    public ResponseEntity<Error> refTaken() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Error("EXTERNAL_REF_TAKEN"));
    }

    @ExceptionHandler(CustomerRecords.AccountAlreadyHeld.class)
    public ResponseEntity<Error> alreadyHeld() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Error("ACCOUNT_ALREADY_HELD"));
    }

    public record Error(String code) {}
}
