package org.elyonar.fincore.ledger.posting;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.ledger.period.PeriodService;
import org.elyonar.fincore.ledger.outbox.LedgerEvent;
import org.elyonar.fincore.ledger.outbox.OutboxWriter;
import org.elyonar.fincore.ledger.shared.ErrorCode;
import org.elyonar.fincore.ledger.shared.LedgerException;
import org.elyonar.fincore.ledger.tenant.TenantConfig;
import org.elyonar.fincore.ledger.tenant.TenantConfigService;
import org.elyonar.fincore.ledger.tenant.TenantScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Posts balanced transactions. The order of operations here is the design, not a preference —
 * see {@code docs/posting-algorithm.md}.
 *
 * <p>Everything happens inside one database transaction at READ COMMITTED: complete or absent,
 * never partial. A rejection leaves no transaction row, no entries, no balance change and no
 * event, which is what lets the idempotency key stay free for a genuine retry.
 */
@Service
public class PostingService {

    /** Entry-count ceiling. A contract-legal transaction must not be able to lock the world. */
    private static final int MAX_ENTRIES = 100;

    private static final long MAX_AMOUNT_MINOR = 1_000_000_000_000_000L;

    private final TenantScope tenantScope;
    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final TenantConfigService tenantConfig;
    private final PeriodService periods;

    public PostingService(
            TenantScope tenantScope,
            JdbcTemplate jdbc,
            OutboxWriter outbox,
            TenantConfigService tenantConfig,
            PeriodService periods) {
        this.tenantScope = tenantScope;
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.tenantConfig = tenantConfig;
        this.periods = periods;
    }

    public PostingResult post(PostTransactionCommand command) {
        String fingerprint = RequestFingerprint.of(command);
        return tenantScope.inTenant(command.tenantId(), () -> doPost(command, fingerprint));
    }

    private PostingResult doPost(PostTransactionCommand command, String fingerprint) {
        // 1. Idempotency. A replay must not re-validate: the original already committed, and
        //    re-running validation could reject on conditions that changed since.
        var existing = findByKey(command.tenantId(), command.idempotencyKey());
        if (existing != null) {
            return replayOrReject(existing, fingerprint);
        }

        TenantConfig config = tenantConfig.currentFor(command.tenantId());
        validateShape(command, config);

        // 3. Value dates. Unsupplied means "today, in the tenant's zone" — resolved only now,
        //    after the fingerprint was taken over the request as received.
        LocalDate businessDate = config.businessDate();
        List<EntryLine> entries = resolveValueDates(command.entries(), businessDate);
        validateValueDates(command, entries, config, businessDate);

        // 4. Register the transaction. The unique index — not application code — arbitrates the
        //    race: a concurrent duplicate blocks on it, then loses and replays the winner.
        UUID transactionId = UUID.randomUUID();
        int registered =
                jdbc.update(
                        """
                        INSERT INTO ledger_transactions
                            (id, tenant_id, idempotency_key, request_fingerprint, initiated_by,
                             executed_by, relates_to_transaction_id, backdate_reason)
                        VALUES (?,?,?,?,?,?,?,?)
                        ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                        """,
                        transactionId,
                        command.tenantId(),
                        command.idempotencyKey(),
                        fingerprint,
                        command.initiatedBy(),
                        command.executedBy(),
                        command.relatesToTransactionId(),
                        command.backdateReason());

        if (registered == 0) {
            // Lost the race. ON CONFLICT rather than catching the violation is deliberate: a
            // raised constraint error aborts the PostgreSQL transaction, so the "re-read the
            // winner" query that makes replay possible would itself fail with 25P02. DO NOTHING
            // still blocks on the conflicting row until the winner commits, so the arbitration is
            // unchanged — only the failure mode is survivable.
            var winner = findByKey(command.tenantId(), command.idempotencyKey());
            if (winner == null) {
                throw new IllegalStateException(
                        "idempotency key conflicted but no winner is visible; this should be unreachable");
            }
            return replayOrReject(winner, fingerprint);
        }

        // 5. Tier-1 lock: the compensation target, before any balance row. Reversal takes the
        //    same lock in the same order, which is what makes the exclusion between them both
        //    race-safe and deadlock-free — two operations that need a transaction row and balance
        //    rows must never acquire the two classes in opposite orders.
        if (command.relatesToTransactionId() != null) {
            lockTransaction(command.tenantId(), command.relatesToTransactionId());
            String targetStatus =
                    jdbc.queryForObject(
                            "SELECT status FROM ledger_transactions WHERE tenant_id = ? AND id = ?",
                            String.class,
                            command.tenantId(),
                            command.relatesToTransactionId());
            if ("REVERSED".equals(targetStatus)) {
                // Compensating an undone transaction is the same double credit as reversing a
                // compensated one, with the operations swapped.
                throw new LedgerException(
                        ErrorCode.TARGET_REVERSED,
                        "cannot compensate transaction " + command.relatesToTransactionId() + ": it is reversed");
            }
        }

        // 6. Tier-2 locks, in sorted account order. One global ordering is what makes deadlock
        //    impossible rather than unlikely.
        Map<UUID, Long> deltaByAccount = netDeltas(entries);
        List<UUID> lockOrder = deltaByAccount.keySet().stream().sorted(Comparator.naturalOrder()).toList();
        for (UUID accountId : lockOrder) {
            lockBalance(command.tenantId(), accountId);
        }

        validateAccounts(command.tenantId(), entries);

        // Consume a hold, if asked. This commits with the entries, which is the whole point: a
        // release-then-post saga leaves a window in which concurrent spending can take funds the
        // bank has already settled externally, and no amount of care outside the database closes
        // it.
        if (command.consumeHoldId() != null) {
            consumeHold(command.tenantId(), command.consumeHoldId(), transactionId, deltaByAccount);
        }

        // 7-8. Write entries, then apply net deltas per account.
        for (EntryLine entry : entries) {
            jdbc.update(
                    """
                    INSERT INTO entries
                        (transaction_id, account_id, tenant_id, direction, amount_minor, currency, value_date)
                    VALUES (?,?,?,?,?,?,?)
                    """,
                    transactionId,
                    entry.accountId(),
                    command.tenantId(),
                    entry.direction().name(),
                    entry.amountMinor(),
                    entry.currency(),
                    entry.valueDate());
        }
        for (UUID accountId : lockOrder) {
            applyDelta(command.tenantId(), accountId, deltaByAccount.get(accountId));
        }

        // 9. The event, in this same transaction: it exists if and only if the posting committed.
        outbox.write(
                command.tenantId(),
                LedgerEvent.POSTING_COMPLETED,
                transactionId,
                Map.of(
                        "transactionId", transactionId.toString(),
                        "idempotencyKey", command.idempotencyKey(),
                        "entryCount", entries.size()));

        return PostingResult.posted(transactionId);
    }

    private PostingResult replayOrReject(Registered existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            // Loud, never a silent wrong answer: the caller believes new money moved.
            throw new LedgerException(
                    ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "idempotency key already registered against a different payload");
        }
        return PostingResult.replay(existing.id());
    }

    private void validateShape(PostTransactionCommand command, TenantConfig config) {
        List<EntryLine> entries = command.entries();
        if (entries.size() < 2) {
            throw new LedgerException(ErrorCode.UNBALANCED, "a transaction needs at least two entries");
        }
        int cap = Math.min(config.maxEntriesPerTransaction(), MAX_ENTRIES);
        if (entries.size() > cap) {
            throw new LedgerException(
                    ErrorCode.LIMIT_EXCEEDED, "a transaction may carry at most " + cap + " entries");
        }
        if (command.idempotencyKey() == null || command.idempotencyKey().length() > 200) {
            throw new LedgerException(ErrorCode.LIMIT_EXCEEDED, "idempotency key must be 1..200 characters");
        }
        for (EntryLine entry : entries) {
            if (entry.amountMinor() <= 0) {
                throw new LedgerException(ErrorCode.LIMIT_EXCEEDED, "entry amounts must be positive");
            }
            if (entry.amountMinor() > MAX_AMOUNT_MINOR) {
                throw new LedgerException(ErrorCode.LIMIT_EXCEEDED, "entry amount exceeds the 10^15 cap");
            }
        }

        // An account on both sides moves nothing while inflating volume and planting text in an
        // immutable record. Rejected outright rather than tolerated.
        long distinctAccounts = entries.stream().map(EntryLine::accountId).distinct().count();
        long debitAccounts =
                entries.stream()
                        .filter(e -> e.direction() == EntryLine.Direction.DEBIT)
                        .map(EntryLine::accountId)
                        .distinct()
                        .count();
        long creditAccounts =
                entries.stream()
                        .filter(e -> e.direction() == EntryLine.Direction.CREDIT)
                        .map(EntryLine::accountId)
                        .distinct()
                        .count();
        if (debitAccounts + creditAccounts > distinctAccounts) {
            throw new LedgerException(
                    ErrorCode.WASH_TRANSACTION, "an account may not appear on both sides of one transaction");
        }

        // Balance is checked per currency, not overall: a multi-currency transaction that nets to
        // zero across currencies is not balanced, it is an unrecorded FX position.
        Map<String, Long> netByCurrency = new LinkedHashMap<>();
        for (EntryLine entry : entries) {
            netByCurrency.merge(entry.currency(), entry.signedMinor(), Long::sum);
        }
        netByCurrency.forEach(
                (currency, net) -> {
                    if (net != 0L) {
                        throw new LedgerException(
                                ErrorCode.UNBALANCED, "debits and credits differ in " + currency);
                    }
                });
    }

    /**
     * Value dating is bounded and governed, because an unbounded one is a way to rewrite history.
     *
     * <p>Future dates are refused outright: a ledger records what happened, not what is expected
     * to. Backdating is allowed within a tenant window and only with a stated reason, and never
     * into a closed accounting period — that last rule is what keeps a signed-off statement
     * reproducible forever.
     */
    private void validateValueDates(
            PostTransactionCommand command, List<EntryLine> entries, TenantConfig config, LocalDate businessDate) {
        LocalDate earliest = config.earliestAllowedValueDate();
        boolean anyBackdated = false;

        for (EntryLine entry : entries) {
            LocalDate valueDate = entry.valueDate();
            if (valueDate.isAfter(businessDate)) {
                throw new LedgerException(
                        ErrorCode.VALUE_DATE_INVALID, "value date " + valueDate + " is in the future");
            }
            if (valueDate.isBefore(businessDate)) {
                anyBackdated = true;
                if (valueDate.isBefore(earliest)) {
                    throw new LedgerException(
                            ErrorCode.VALUE_DATE_INVALID,
                            "value date " + valueDate + " is older than the tenant's backdate window");
                }
                if (periods.isClosed(command.tenantId(), valueDate)) {
                    throw new LedgerException(
                            ErrorCode.VALUE_DATE_INVALID,
                            "value date " + valueDate + " falls in a closed accounting period");
                }
            }
        }

        if (anyBackdated && (command.backdateReason() == null || command.backdateReason().isBlank())) {
            // Backdating is legitimate and routine; doing it unexplained is not.
            throw new LedgerException(
                    ErrorCode.VALUE_DATE_INVALID, "backdated postings require a stated reason");
        }
    }

    private static List<EntryLine> resolveValueDates(List<EntryLine> entries, LocalDate businessDate) {
        return entries.stream()
                .map(
                        e ->
                                e.valueDate() != null
                                        ? e
                                        : new EntryLine(
                                                e.accountId(), e.direction(), e.amountMinor(), e.currency(), businessDate))
                .toList();
    }

    private static Map<UUID, Long> netDeltas(List<EntryLine> entries) {
        Map<UUID, Long> deltas = new LinkedHashMap<>();
        for (EntryLine entry : entries) {
            deltas.merge(entry.accountId(), entry.signedMinor(), Long::sum);
        }
        return deltas;
    }

    private void consumeHold(UUID tenantId, UUID holdId, UUID transactionId, Map<UUID, Long> deltaByAccount) {
        var hold =
                jdbc.query(
                        "SELECT account_id, amount_minor, status FROM holds WHERE tenant_id = ? AND id = ?",
                        rs ->
                                rs.next()
                                        ? new Object[] {rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3)}
                                        : null,
                        tenantId,
                        holdId);
        if (hold == null) {
            throw new LedgerException(ErrorCode.ACCOUNT_NOT_FOUND, "unknown hold " + holdId);
        }

        UUID heldAccount = (UUID) hold[0];
        long heldAmount = (Long) hold[1];
        String status = (String) hold[2];

        if (!"ACTIVE".equals(status)) {
            // Terminal for this hold. Re-authorisation is the only honest recovery — the caller
            // must never be told a reservation still exists when it does not.
            throw new LedgerException(
                    ErrorCode.HOLD_NOT_ACTIVE, "hold " + holdId + " is " + status + " and cannot be captured");
        }

        Long delta = deltaByAccount.get(heldAccount);
        if (delta == null) {
            throw new LedgerException(
                    ErrorCode.HOLD_NOT_ACTIVE, "the consumed hold is not on any account this posting touches");
        }

        long debited = delta < 0 ? -delta : 0L;
        if (debited > heldAmount) {
            throw new LedgerException(
                    ErrorCode.HOLD_EXCEEDED, "the debit against the held account exceeds the reserved amount");
        }

        // Single-shot capture: the hold is consumed in full and any unspent remainder is released
        // explicitly, rather than lingering as a reservation nobody will clear. Orchestration
        // places a fresh hold if it needs another.
        jdbc.update(
                "UPDATE holds SET status='CONSUMED', resolved_at=now(), consumed_by_transaction_id=?"
                        + " WHERE tenant_id = ? AND id = ?",
                transactionId,
                tenantId,
                holdId);
        jdbc.update(
                "UPDATE balances SET holds_total_minor = holds_total_minor - ?, updated_at = now()"
                        + " WHERE tenant_id = ? AND account_id = ?",
                heldAmount,
                tenantId,
                heldAccount);

        // Both amounts, so a consumer can see that the remainder was released rather than
        // silently vanishing.
        outbox.write(
                tenantId,
                LedgerEvent.HOLD_RELEASED,
                holdId,
                Map.of(
                        "holdId", holdId.toString(),
                        "reason", "consumed",
                        "capturedMinor", debited,
                        "releasedRemainderMinor", heldAmount - debited,
                        "transactionId", transactionId.toString()));
    }

    /** Tier-1 lock. Always taken before any balance row, by every operation that needs both. */
    private void lockTransaction(UUID tenantId, UUID transactionId) {
        Integer locked =
                jdbc.query(
                        "SELECT 1 FROM ledger_transactions WHERE tenant_id = ? AND id = ? FOR UPDATE",
                        rs -> rs.next() ? 1 : 0,
                        tenantId,
                        transactionId);
        if (locked == null || locked == 0) {
            throw new LedgerException(ErrorCode.ACCOUNT_NOT_FOUND, "unknown transaction " + transactionId);
        }
    }

    private void lockBalance(UUID tenantId, UUID accountId) {
        Integer locked =
                jdbc.query(
                        "SELECT 1 FROM balances WHERE tenant_id = ? AND account_id = ? FOR UPDATE",
                        rs -> rs.next() ? 1 : 0,
                        tenantId,
                        accountId);
        if (locked == null || locked == 0) {
            // Indistinguishable from another tenant's account, deliberately.
            throw new LedgerException(ErrorCode.ACCOUNT_NOT_FOUND, "unknown account " + accountId);
        }
    }

    private void validateAccounts(UUID tenantId, List<EntryLine> entries) {
        for (EntryLine entry : entries) {
            var account =
                    jdbc.query(
                            "SELECT status, currency FROM accounts WHERE tenant_id = ? AND id = ?",
                            rs -> rs.next() ? new String[] {rs.getString(1), rs.getString(2).trim()} : null,
                            tenantId,
                            entry.accountId());
            if (account == null) {
                throw new LedgerException(ErrorCode.ACCOUNT_NOT_FOUND, "unknown account " + entry.accountId());
            }
            if (!"OPEN".equals(account[0])) {
                throw new LedgerException(ErrorCode.ACCOUNT_CLOSED, "account " + entry.accountId() + " is closed");
            }
            if (!account[1].equals(entry.currency())) {
                throw new LedgerException(
                        ErrorCode.CURRENCY_MISMATCH,
                        "entry currency " + entry.currency() + " does not match account currency " + account[1]);
            }
        }
    }

    private void applyDelta(UUID tenantId, UUID accountId, long delta) {
        jdbc.update(
                """
                UPDATE balances
                   SET current_minor = current_minor + ?, updated_at = now()
                 WHERE tenant_id = ? AND account_id = ?
                """,
                delta,
                tenantId,
                accountId);

        // The guard reads back the committed-in-transaction state rather than trusting the delta,
        // so it accounts for holds placed by other operations that this transaction has serialised
        // behind on the same balance row.
        Boolean breached =
                jdbc.queryForObject(
                        """
                        SELECT NOT a.allow_negative AND (b.current_minor - b.holds_total_minor) < 0
                          FROM balances b
                          JOIN accounts a ON a.tenant_id = b.tenant_id AND a.id = b.account_id
                         WHERE b.tenant_id = ? AND b.account_id = ?
                        """,
                        Boolean.class,
                        tenantId,
                        accountId);
        if (Boolean.TRUE.equals(breached)) {
            throw new LedgerException(
                    ErrorCode.INSUFFICIENT_FUNDS, "account " + accountId + " would go available < 0");
        }
    }

    private Registered findByKey(UUID tenantId, String idempotencyKey) {
        return jdbc.query(
                "SELECT id, request_fingerprint FROM ledger_transactions WHERE tenant_id = ? AND idempotency_key = ?",
                rs -> rs.next() ? new Registered(rs.getObject(1, UUID.class), rs.getString(2)) : null,
                tenantId,
                idempotencyKey);
    }

    private record Registered(UUID id, String fingerprint) {}
}
