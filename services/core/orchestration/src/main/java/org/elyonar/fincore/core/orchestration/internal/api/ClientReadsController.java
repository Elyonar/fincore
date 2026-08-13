package org.elyonar.fincore.core.orchestration.internal.api;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.auth.Authorization;
import org.elyonar.fincore.core.orchestration.api.CustomerEligibility;
import org.elyonar.fincore.core.orchestration.api.CoreException;
import org.elyonar.fincore.core.orchestration.api.ErrorCode;
import org.elyonar.fincore.core.orchestration.internal.approval.ApprovalRecords;
import org.elyonar.fincore.core.orchestration.internal.ledger.LedgerClient;
import org.elyonar.fincore.core.orchestration.internal.saga.SagaRecords;
import org.elyonar.fincore.core.orchestration.internal.saga.TillRecords;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The reads client screens open with (ui-runway.md §3, ADR 0014) — shapes over existing facts,
 * never new business rules.
 *
 * <p>Two of these proxy the ledger, because clients never address it: balances joined onto the
 * customer's held accounts, and the statement passed through <em>byte-for-byte</em> — the
 * ledger's statement contract (period-bounded, {@code opening + Σ movements = closing}, final vs
 * interim) is the product feature and Core must not blur it. "Could not ask" is a 503, never an
 * invented answer — the outcome discipline, applied to reads.
 */
@Tag(name = "Client reads", description = "Screen-shaped reads: accounts, statements, till activity, approvals")
@RestController
@RequestMapping("/v1")
public class ClientReadsController {

    private final CustomerEligibility customers;
    private final LedgerClient ledger;
    private final TillRecords tills;
    private final ApprovalRecords approvals;
    private final SagaRecords sagas;
    private final JsonMapper json = JsonMapper.builder().build();

    private final org.elyonar.fincore.core.orchestration.internal.TenantZones zones;

    public ClientReadsController(
            CustomerEligibility customers,
            LedgerClient ledger,
            TillRecords tills,
            ApprovalRecords approvals,
            SagaRecords sagas,
            org.elyonar.fincore.core.orchestration.internal.TenantZones zones) {
        this.customers = customers;
        this.ledger = ledger;
        this.tills = tills;
        this.approvals = approvals;
        this.sagas = sagas;
        this.zones = zones;
    }

    /** The customer-360 money view: held accounts with the ledger's balances joined on. */
    @GetMapping("/customers/{id}/accounts")
    public Map<String, Object> accounts(@PathVariable UUID id) {
        var identity = Authorization.require("customers:read");
        List<CustomerEligibility.HeldAccount> held = customers.heldAccounts(identity.tenantId(), id);

        List<Map<String, Object>> out = new ArrayList<>();
        for (CustomerEligibility.HeldAccount account : held) {
            LedgerClient.RawRead read =
                    ledger.get(identity.tenantId(), "/v1/accounts/" + account.ledgerAccountId());
            if (read.unreachable() || read.status() >= 500) {
                // A balance we could not ask for is not a balance of zero.
                throw new CoreException(ErrorCode.LEDGER_UNREACHABLE, "could not read balances");
            }
            var row = new LinkedHashMap<String, Object>();
            row.put("ledgerAccountId", account.ledgerAccountId().toString());
            // The account's own name. Every screen that had only the UUID showed the customer eight
            // hexadecimal characters and called it an account.
            row.put("accountNumber", account.accountNumber());
            row.put("currency", account.currency());
            row.put("role", account.role());
            // What prices every transaction on this account. Null on accounts opened before the
            // platform recorded it, and worth showing as null rather than omitting: a teller
            // looking at a customer-360 should see that this one cannot take a deposit.
            row.put("productCode", account.productCode());
            if (read.status() == 200) {
                JsonNode parsed = json.readTree(read.body());
                row.put("currentMinor", parsed.path("currentMinor").asString());
                row.put("availableMinor", parsed.path("availableMinor").asString());
                row.put("holdsMinor", parsed.path("holdsMinor").asString());
            }
            out.add(row);
        }
        return Map.of("customerId", id.toString(), "accounts", out);
    }

    /**
     * The statement as the Ledger answers it, with our reference added to each line.
     *
     * <p>Status and body still travel untouched in every respect a client depends on — the
     * indistinguishable 404, the interim label, the opening and closing figures, the ordering, the
     * cursor. Nothing is removed and nothing is recomputed. One field is <em>added</em> per line.
     *
     * <p>It is added because a statement is the Ledger's document and names the Ledger's
     * transactions, while the customer holding a receipt has ours. Without the join, the line a
     * customer is querying and the reference they are reading out are unrelated strings, and the
     * teller has no way to get from one to the other. A line whose posting this institution did not
     * originate — there are none today, but the Ledger is a general one — simply has no reference,
     * which is the truth about it rather than a gap to be filled in.
     */
    @GetMapping("/accounts/{ledgerAccountId}/statement")
    public ResponseEntity<String> statement(
            @PathVariable UUID ledgerAccountId, @RequestParam String from, @RequestParam String to) {
        var identity = Authorization.require("transfers:read");
        requireDate(from, "from");
        requireDate(to, "to");
        LedgerClient.RawRead read =
                ledger.get(
                        identity.tenantId(),
                        "/v1/accounts/" + ledgerAccountId + "/entries?from=" + from + "&to=" + to);
        if (read.unreachable()) {
            throw new CoreException(ErrorCode.LEDGER_UNREACHABLE, "could not read the statement");
        }
        return ResponseEntity.status(read.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(withReferences(identity.tenantId(), read));
    }

    /**
     * Adds {@code reference} to each statement line, leaving everything else exactly as it arrived.
     *
     * <p>Anything unexpected — a non-200, an unparseable body, a shape without lines — returns the
     * original untouched. A statement that could not be annotated is still a statement; one that
     * failed to be served because the annotation threw would be a read broken by a convenience.
     */
    private String withReferences(UUID tenantId, LedgerClient.RawRead read) {
        if (read.status() != 200 || read.body() == null) {
            return read.body();
        }
        try {
            JsonNode parsed = json.readTree(read.body());
            JsonNode lines = parsed.path("lines");
            if (!lines.isArray() || lines.isEmpty()) {
                return read.body();
            }

            var ledgerTransactionIds = new LinkedHashSet<UUID>();
            for (JsonNode line : lines) {
                String id = line.path("transactionId").asString(null);
                if (id != null) {
                    try {
                        ledgerTransactionIds.add(UUID.fromString(id));
                    } catch (IllegalArgumentException notAUuid) {
                        // The Ledger's own identifier shape is its business; skip what we cannot key on.
                    }
                }
            }

            Map<UUID, String> references = sagas.referencesForLedgerTransactions(tenantId, ledgerTransactionIds);
            if (references.isEmpty()) {
                return read.body();
            }
            for (JsonNode line : lines) {
                String id = line.path("transactionId").asString(null);
                String reference = id == null ? null : references.get(UUID.fromString(id));
                if (reference != null && line.isObject()) {
                    ((tools.jackson.databind.node.ObjectNode) line).put("reference", reference);
                }
            }
            return json.writeValueAsString(parsed);
        } catch (Exception annotationFailed) {
            return read.body();
        }
    }

    /** A till's day: every saga that touched its account on the date, with the net position. */
    @GetMapping("/tills/{id}/activity")
    public Map<String, Object> tillActivity(@PathVariable UUID id, @RequestParam String date) {
        var identity = Authorization.require("tills:read");
        requireDate(date, "date");
        UUID accountId = tills.ledgerAccountOf(identity.tenantId(), id);
        if (accountId == null) {
            throw new CoreException(ErrorCode.TILL_NOT_OPEN, "no such till");
        }
        List<Map<String, Object>> movements =
                tills.dayActivity(
                        identity.tenantId(),
                        accountId,
                        date,
                        zones.businessZone(identity.tenantId()).getId());
        long in =
                movements.stream()
                        .filter(m -> "IN".equals(m.get("direction")) && "COMPLETED".equals(m.get("state")))
                        .mapToLong(m -> Long.parseLong((String) m.get("amountMinor")))
                        .sum();
        long out =
                movements.stream()
                        .filter(m -> "OUT".equals(m.get("direction")) && "COMPLETED".equals(m.get("state")))
                        .mapToLong(m -> Long.parseLong((String) m.get("amountMinor")))
                        .sum();
        var view = new LinkedHashMap<String, Object>();
        view.put("tillId", id.toString());
        view.put("date", date);
        view.put("movements", movements);
        view.put("completedInMinor", Long.toString(in));
        view.put("completedOutMinor", Long.toString(out));
        view.put("netMinor", Long.toString(in - out));
        return view;
    }

    /**
     * A date parameter is a date before it is anything else — validated here so garbage is a 422
     * at the door rather than a 500 from the database or an oddly-shaped ledger query.
     */
    private static void requireDate(String value, String field) {
        try {
            java.time.LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            throw new CoreException(ErrorCode.COMMAND_INVALID, field + " must be an ISO date (yyyy-MM-dd)");
        }
    }

    /** The checker's queue: approvals awaiting a decision, oldest first. */
    @GetMapping("/approvals/pending")
    public Map<String, Object> pendingApprovals() {
        var identity = Authorization.require("approvals:check");
        return Map.of("approvals", approvals.pending(identity.tenantId()));
    }
}
