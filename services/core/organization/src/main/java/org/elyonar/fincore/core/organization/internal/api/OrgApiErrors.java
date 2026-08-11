package org.elyonar.fincore.core.organization.internal.api;

import org.elyonar.fincore.core.organization.api.OrganizationErrorCode;
import org.elyonar.fincore.core.organization.internal.UnitRecords;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Organization's own error mapping — module-local by necessity, like {@code CustomerApiErrors}:
 * a shared advice would have to import this module's internals, which {@code ModuleBoundaryTest}
 * forbids. Authentication and authorization failures are mapped once, globally, by
 * {@code libs/auth}.
 */
@RestControllerAdvice(assignableTypes = OrgUnitController.class)
public class OrgApiErrors {

    /** Absent, another tenant's, or closed. One status for all three, deliberately. */
    @ExceptionHandler(UnitRecords.NoSuchUnit.class)
    public ResponseEntity<Error> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new Error(OrganizationErrorCode.UNIT_NOT_FOUND.code()));
    }

    @ExceptionHandler(UnitRecords.NoSuchParent.class)
    public ResponseEntity<Error> parentNotFound() {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new Error(OrganizationErrorCode.PARENT_UNIT_NOT_FOUND.code()));
    }

    @ExceptionHandler(UnitRecords.CodeTaken.class)
    public ResponseEntity<Error> codeTaken() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new Error(OrganizationErrorCode.UNIT_CODE_TAKEN.code()));
    }

    /** 422, not 409: the code is refused on its own terms, not because something else holds it. */
    @ExceptionHandler(UnitRecords.MalformedCode.class)
    public ResponseEntity<Error> malformedCode() {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new Error(OrganizationErrorCode.UNIT_CODE_INVALID.code()));
    }

    @ExceptionHandler(UnitRecords.AlreadyAssigned.class)
    public ResponseEntity<Error> alreadyAssigned() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new Error(OrganizationErrorCode.ASSIGNMENT_EXISTS.code()));
    }

    @ExceptionHandler(UnitRecords.NoSuchAssignment.class)
    public ResponseEntity<Error> assignmentNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new Error(OrganizationErrorCode.ASSIGNMENT_NOT_FOUND.code()));
    }

    public record Error(String code) {}
}
