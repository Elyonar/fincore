package org.elyonar.fincore.core.app.admin;

import java.util.LinkedHashMap;
import java.util.Map;
import org.elyonar.fincore.core.organization.api.OrganizationUnits;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.databind.JsonNode;

/**
 * The administration surface's error mapping, including the translation admin-surface §5 promised.
 *
 * <p>The directory has its own catalog and this one is Core's; where the names differ, Core's wins
 * on Core's wire ({@code ROLE_UNKNOWN} → {@code ROLE_NOT_FOUND}). A client should never have to
 * learn that two services are involved to understand a refusal.
 */
@RestControllerAdvice(assignableTypes = AdminController.class)
public class AdminApiErrors {

    /** Core's spelling for a directory code, where the two catalogs differ. */
    private static String translate(String code) {
        if (code == null) {
            return "DIRECTORY_ERROR";
        }
        return switch (code) {
            case "ROLE_UNKNOWN" -> "ROLE_NOT_FOUND";
            default -> code;
        };
    }

    @ExceptionHandler(IdentityDirectory.DirectoryRefused.class)
    public ResponseEntity<Map<String, Object>> refused(IdentityDirectory.DirectoryRefused e) {
        JsonNode body = e.body;
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", translate(body == null || body.get("code") == null ? null : body.get("code").asString()));
        error.put(
                "reason",
                body == null || body.get("reason") == null || body.get("reason").isNull()
                        ? null
                        : body.get("reason").asString());
        error.put(
                "message",
                body == null || body.get("message") == null
                        ? "the directory refused the request"
                        : body.get("message").asString());
        error.put("details", body == null || body.get("details") == null ? Map.of() : body.get("details"));
        return ResponseEntity.status(e.status).body(error);
    }

    /**
     * The directory did not answer. 503 and not 500: the request was well formed, nothing was
     * written, and trying again shortly is genuinely the right advice.
     */
    @ExceptionHandler(IdentityDirectory.DirectoryUnreachable.class)
    public ResponseEntity<Map<String, Object>> unreachable(IdentityDirectory.DirectoryUnreachable e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "code", "DIRECTORY_UNREACHABLE",
                        "message", "the staff directory could not be reached",
                        "details", Map.of()));
    }

    /** A unit code naming no active unit of this tenant. Nothing was written in either store. */
    @ExceptionHandler(OrganizationUnits.NoSuchUnit.class)
    public ResponseEntity<Map<String, Object>> noSuchUnit(OrganizationUnits.NoSuchUnit e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "code", "UNIT_NOT_FOUND",
                        "message", "no active organizational unit with that code",
                        "details", Map.of("code", e.code)));
    }
}
