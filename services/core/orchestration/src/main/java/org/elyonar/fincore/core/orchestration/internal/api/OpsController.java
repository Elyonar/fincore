package org.elyonar.fincore.core.orchestration.internal.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.auth.Authorization;
import org.elyonar.fincore.core.orchestration.internal.approval.ApprovalRecords;
import org.elyonar.fincore.core.orchestration.internal.saga.OpsCases;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Maker-checker approvals and the unresolved-outcome queue.
 *
 * <p>These were in {@code api.md} and reachable only from code until now, which is a gap worth
 * closing precisely because the alternative is an operator with no way to do their job except
 * through the database.
 *
 * <p>Note what is <em>not</em> here: no endpoint by which a human declares that an uncertain
 * transaction posted. {@code resolve} asks Core to try again and see what the Ledger says. The
 * moment an operator can assert an outcome, the outcome protocol's central guarantee becomes
 * advisory — and the pressure to add exactly that endpoint arrives on the worst day, from the
 * person least able to weigh it.
 */
@RestController
@RequestMapping("/v1")
public class OpsController {

    private final ApprovalRecords approvals;
    private final OpsCases cases;

    public OpsController(ApprovalRecords approvals, OpsCases cases) {
        this.approvals = approvals;
        this.cases = cases;
    }

    /** Raises a pending approval, bound to one target and one amount. */
    @PostMapping("/approvals")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> raise(@RequestBody RaiseApproval request) {
        var identity = Authorization.require("approvals:make");
        UUID id =
                approvals.raise(
                        identity.tenantId(),
                        request.targetTransactionId(),
                        request.amountMinor(),
                        Authorization.initiatedBy());
        return Map.of("approvalId", id.toString(), "status", "PENDING");
    }

    /**
     * Records a checker's decision.
     *
     * <p>The checker is taken from the token, never the body — an approval whose second signature
     * a caller could name is not maker-checker. The database refuses maker == checker regardless.
     */
    @PostMapping("/approvals/{id}/check")
    public Map<String, Object> check(@PathVariable UUID id, @RequestBody CheckApproval request) {
        var identity = Authorization.require("approvals:check");
        approvals.check(identity.tenantId(), id, request.approved(), Authorization.initiatedBy());
        return Map.of("approvalId", id.toString(), "status", request.approved() ? "APPROVED" : "REJECTED");
    }

    /** The unresolved-outcome queue: transactions whose fate is still being determined. */
    @GetMapping("/ops/cases")
    public List<Map<String, Object>> openCases() {
        var identity = Authorization.require("ops:read");
        return cases.open(identity.tenantId());
    }

    /**
     * Asks Core to try resolving the case again, now.
     *
     * <p>Takes no outcome, deliberately. It re-sends the derived key and lets the Ledger answer;
     * the case closes when the Ledger is definitive, not when a human is confident.
     */
    @PostMapping("/ops/cases/{id}/resolve")
    public Map<String, Object> resolve(@PathVariable UUID id) {
        var identity = Authorization.require("ops:resolve");
        return cases.reattempt(identity.tenantId(), id);
    }

    /** @param amountMinor must match the target exactly; an approval is bound to one amount */
    public record RaiseApproval(UUID targetTransactionId, long amountMinor) {}

    public record CheckApproval(boolean approved) {}
}
