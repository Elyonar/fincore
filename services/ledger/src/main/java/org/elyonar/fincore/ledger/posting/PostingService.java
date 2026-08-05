package org.elyonar.fincore.ledger.posting;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.ledger.shared.ErrorCode;
import org.elyonar.fincore.ledger.shared.LedgerException;
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

    public PostingService(TenantScope tenantScope, JdbcTemplate jdbc) {
        this.tenantScope = tenantScope;
        this.jdbc = jdbc;
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

        validateShape(command);

        // 3. Value dates. Unsupplied means "today, in the tenant's zone" — resolved only now,
        //    after the fingerprint was taken over the request as received.
        LocalDate businessDate = LocalDate.now();
        List<EntryLine> entries = resolveValueDates(command.entries(), businessDate);

        // 4. Register the transaction. The unique index — not application code — arbitrates the
        //    race: a concurrent duplicate blocks on it, then loses and replays the winner.
        UUID transactionId = UUID.randomUUID();
        int registered =
                jdbc.update(
                        """
                        INSERT INTO ledger_transactions
                            (id, tenant_id, idempotency_key, request_fingerprint, initiated_by, executed_by)
                        VALUES (?,?,?,?,?,?)
                        ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                        """,
                        transactionId,
                        command.tenantId(),
                        command.idempotencyKey(),
                        fingerprint,
                        command.initiatedBy(),
                        command.executedBy());

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

        // 6. Tier-2 locks, in sorted account order. One global ordering is what makes deadlock
        //    impossible rather than unlikely. (Tier 1 — target transaction rows — arrives with
        //    reversal and compensation.)
        Map<UUID, Long> deltaByAccount = netDeltas(entries);
        List<UUID> lockOrder = deltaByAccount.keySet().stream().sorted(Comparator.naturalOrder()).toList();
        for (UUID accountId : lockOrder) {
            lockBalance(command.tenantId(), accountId);
        }

        validateAccounts(command.tenantId(), entries);

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

    private void validateShape(PostTransactionCommand command) {
        List<EntryLine> entries = command.entries();
        if (entries.size() < 2) {
            throw new LedgerException(ErrorCode.UNBALANCED, "a transaction needs at least two entries");
        }
        if (entries.size() > MAX_ENTRIES) {
            throw new LedgerException(
                    ErrorCode.LIMIT_EXCEEDED, "a transaction may carry at most " + MAX_ENTRIES + " entries");
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
