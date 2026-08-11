package org.elyonar.fincore.core.customer.internal.api;

import org.elyonar.fincore.core.customer.internal.CustomerRecords;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.elyonar.fincore.core.customer.api.CustomerAdministration;
import org.elyonar.fincore.core.customer.api.CustomerErrorCode;

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
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Error(CustomerErrorCode.CUSTOMER_NOT_FOUND.code()));
    }

    @ExceptionHandler(CustomerRecords.ExternalRefTaken.class)
    public ResponseEntity<Error> refTaken() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Error(CustomerErrorCode.EXTERNAL_REF_TAKEN.code()));
    }

    /**
     * The number was supplied and is already somebody else's.
     *
     * <p>Separate from {@code ACCOUNT_ALREADY_HELD} because the remedies are different: that one
     * means this customer already holds the account, this one means the number belongs to another.
     * A caller that cannot tell them apart cannot write either sentence.
     */
    @ExceptionHandler(CustomerAdministration.AccountNumberTaken.class)
    public ResponseEntity<Error> accountNumberTaken() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Error(CustomerErrorCode.ACCOUNT_NUMBER_TAKEN.code()));
    }

    @ExceptionHandler(CustomerAdministration.AccountAlreadyHeld.class)
    public ResponseEntity<Error> alreadyHeld() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Error(CustomerErrorCode.ACCOUNT_ALREADY_HELD.code()));
    }

    public record Error(String code) {}
}
