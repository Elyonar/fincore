package org.elyonar.fincore.core.orchestration.internal.saga;

import java.util.UUID;
import org.elyonar.fincore.core.orchestration.api.TransferCommand;
import org.elyonar.fincore.core.orchestration.api.TransactionDetail;
import org.elyonar.fincore.core.orchestration.api.TransferResult;
import org.elyonar.fincore.core.product.api.ProductDecision;
import org.elyonar.fincore.core.orchestration.internal.outbox.OutboxWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.elyonar.fincore.core.orchestration.api.CoreProperties;

/**
 * The saga's own writes: Phase A's open, Phase C's outcome, and the replay lookup.
 *
 * <p>Plain SQL, for the reasons the design records: the correctness here is a unique index
 * arbitrating duplicates, a reservation and a saga row committing together, and a statement order
 * that is deliberate.
 */
@Repository
public class SagaRecords {

    private final JdbcTemplate jdbc;
    private final JdbcTemplate workerJdbc;
    private final OutboxWriter outbox;

    public SagaRecords(
            @Qualifier(CoreProperties.Beans.ORCHESTRATION_JDBC) JdbcTemplate orchestrationJdbcTemplate,
            @Qualifier(CoreProperties.Beans.WORKER_JDBC) JdbcTemplate workerJdbcTemplate,
            OutboxWriter outbox) {
        this.jdbc = orchestrationJdbcTemplate;
        this.workerJdbc = workerJdbcTemplate;
        this.outbox = outbox;
    }

    /**
     * A thin payload: identifiers plus the minimum a consumer needs to decide whether to care.
     *
     * <p>Money as a decimal string, matching every other JSON this platform emits — balances and
     * sums elsewhere exceed exact JSON number range, and one rule everywhere means no consumer
     * silently loses precision. Never database-shaped: a payload is a published contract.
     */
    private static String payload(UUID sagaId, long amountMinor, long feeMinor, String currency) {
        return """
               {"transactionId":"%s","amountMinor":"%d","feeMinor":"%d","currency":"%s"}"""
                .formatted(sagaId, amountMinor, feeMinor, currency);
    }

    /**
     * The immutable snapshot of the decision this saga was opened under — the answer to "why was
     * this fee ₦20 last March" that survives the configuration moving on. Amounts as decimal
     * strings inside JSON, matching every other JSON this platform emits.
     */
    private static String decisionSnapshot(ProductDecision decision, String channel, String kycTier) {
        return """
               {"productVersion":%d,"feeMinor":"%d","feeAccountId":%s,"limitMinor":"%d",               "dailyLimitMinor":%s,"channel":"%s","kycTier":"%s"}"""
                .formatted(
                        decision.productVersion(),
                        decision.feeMinor(),
                        decision.feeAccountId() == null ? "null" : "\"" + decision.feeAccountId() + "\"",
                        decision.limitMinor(),
                        decision.dailyLimitMinor() == null ? "null" : "\"" + decision.dailyLimitMinor() + "\"",
                        channel,
                        kycTier);
    }

    private void scopeTo(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config(\'app.tenant_id\', ?, true)", String.class, tenantId.toString());
    }

    /**
     * The fee-income account this saga will credit: the product's, and only the product's.
     *
     * <p>The caller's account used to stand in when the pricing named none, which was the
     * documented fallback for versions predating the configuration column — and, since nothing
     * could write that column, was in practice the only path. Pricing is authorable now, so the
     * fallback is gone: a caller naming the account its own fee lands in is a caller deciding
     * where the institution's income goes.
     *
     * <p>Still resolved once, here, so the saga row and every worker retry rebuild the identical
     * posting. A fee with no configured account fails the {@code Unretryable} check below rather
     * than posting somewhere plausible.
     */
    private static UUID resolvedFeeAccount(UUID configured, UUID callerSupplied) {
        return configured;
    }

    /**
     * Enforces the day's limit, inside the transaction that reserves against it.
     *
     * <p>Insert-then-verify: the reservation is already written when the window is summed, so two
     * concurrent transfers cannot both observe the limit unbreached — the race the reservation
     * table exists to prevent. The advisory lock serializes writers of the same window (and only
     * them); without it, two READ COMMITTED transactions each see their own insert and neither
     * sees the other's.
     *
     * <p>A breach throws, which rolls back the saga, the reservation and the event together.
     */
    private void enforceDailyLimit(
            UUID tenantId, UUID subjectId, String windowKey, Long dailyLimitMinor) {
        if (dailyLimitMinor == null) {
            return;
        }
        // pg_advisory_xact_lock returns void; the cast gives JDBC something to hand back.
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))::text",
                String.class,
                tenantId + "|" + subjectId + "|" + windowKey);
        Long reserved =
                jdbc.queryForObject(
                        """
                        SELECT COALESCE(SUM(amount_minor), 0)
                          FROM orchestration.limit_reservations
                         WHERE subject_id = ? AND window_key = ?
                           AND status IN ('RESERVED', 'CONSUMED')
                        """,
                        Long.class,
                        subjectId,
                        windowKey);
        if (reserved != null && reserved > dailyLimitMinor) {
            throw new TransferService.TransferRefused(
                    org.elyonar.fincore.core.orchestration.api.ErrorCode.LIMIT_EXCEEDED,
                    org.elyonar.fincore.core.orchestration.api.ErrorReason.DAILY_LIMIT,
                    "the day's limit would be breached",
                    java.util.Map.of());
        }
    }

    /**
     * One saga row as the caller's view of it. Shared by both reads — by id and by reference — so
     * the two can never drift into disagreeing about the same posting.
     */
    private static final org.springframework.jdbc.core.RowMapper<TransferResult> SAGA_AS_RESULT =
            (rs, row) ->
                    new TransferResult(
                            rs.getObject("id", UUID.class),
                            rs.getString("reference"),
                            rs.getString("state"),
                            rs.getLong("amount_minor"),
                            rs.getLong("fee_minor"),
                            rs.getString("currency"),
                            rs.getInt("product_version"),
                            rs.getObject("ledger_transaction_id", UUID.class),
                            rs.getObject("from_account_id", UUID.class),
                            rs.getObject("to_account_id", UUID.class));

    /** A saga already recorded under this key, or null. */
    @Transactional(readOnly = true)
    public Existing findByKey(UUID tenantId, String idempotencyKey) {
        scopeTo(tenantId);
        return jdbc.query(
                """
                SELECT id, reference, request_fingerprint, state, amount_minor, fee_minor, currency,
                       product_version, ledger_transaction_id, from_account_id, to_account_id
                  FROM orchestration.sagas
                 WHERE channel_idempotency_key = ?
                """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    return new Existing(
                            rs.getString("request_fingerprint"),
                            new TransferResult(
                                    rs.getObject("id", UUID.class),
                                    rs.getString("reference"),
                                    rs.getString("state"),
                                    rs.getLong("amount_minor"),
                                    rs.getLong("fee_minor"),
                                    rs.getString("currency"),
                                    rs.getInt("product_version"),
                                    rs.getObject("ledger_transaction_id", UUID.class),
                                    rs.getObject("from_account_id", UUID.class),
                                    rs.getObject("to_account_id", UUID.class)));
                },
                idempotencyKey);
    }

    /**
     * Phase A: reserve the limit and record the saga, together.
     *
     * <p>One transaction, and the reason all three domains share a database. A reservation that
     * committed without its saga would hold headroom nothing will ever release; a saga that
     * committed without its reservation would let a concurrent transfer breach the limit.
     *
     * <p>The saga is persisted <em>before</em> any outbound call, so a crash in the window between
     * "call the Ledger" and "record that we called" is recoverable rather than an orphan posting.
     */
    /**
     * @param productCode the product this transfer was <em>judged</em> by, resolved from the paying
     *     account. Deliberately a parameter rather than {@code command.productCode()}: the command
     *     carries whatever the caller sent, which is accepted and ignored, and a saga row recording
     *     that instead of the resolved one would answer "why was this fee charged" with the
     *     caller's claim rather than the institution's rule. Same correction the cash path made.
     */
    @Transactional
    public UUID open(
            TransferCommand command,
            ProductDecision decision,
            String kycTier,
            String productCode,
            String windowKey) {
        scopeTo(command.tenantId());

        UUID sagaId =
                jdbc.queryForObject(
                        """
                        INSERT INTO orchestration.sagas
                            (tenant_id, type, state, channel_idempotency_key, request_fingerprint,
                             subject_customer_id, product_code, product_version, decision, amount_minor,
                             fee_minor, currency, initiated_by, executed_by,
                             from_account_id, to_account_id, fee_account_id)
                        VALUES (?, 'TRANSFER', 'POSTING', ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """,
                        UUID.class,
                        command.tenantId(),
                        command.idempotencyKey(),
                        command.fingerprint(),
                        command.customerId(),
                        productCode,
                        decision.productVersion(),
                        decisionSnapshot(decision, command.channel(), kycTier),
                        command.amountMinor(),
                        decision.feeMinor(),
                        command.currency(),
                        command.initiatedBy(),
                        command.executedBy(),
                        command.fromAccountId(),
                        command.toAccountId(),
                        resolvedFeeAccount(decision.feeAccountId(), command.feeAccountId()));

        jdbc.update(
                """
                INSERT INTO orchestration.limit_reservations
                    (tenant_id, saga_id, subject_id, limit_type, window_key, amount_minor,
                     currency, expires_at)
                VALUES (?, ?, ?, 'DAILY', ?, ?, ?, now() + INTERVAL '1 day')
                """,
                command.tenantId(),
                sagaId,
                command.customerId(),
                windowKey,
                command.amountMinor() + decision.feeMinor(),
                command.currency());

        enforceDailyLimit(command.tenantId(), command.customerId(), windowKey, decision.dailyLimitMinor());

        // Same transaction as the saga and the reservation: the event exists if and only if the
        // saga does.
        outbox.append(
                command.tenantId(),
                "transfer.initiated",
                sagaId,
                payload(sagaId, command.amountMinor(), decision.feeMinor(), command.currency()));

        return sagaId;
    }

    /**
     * Phase A for cash: the saga, the reservation, and the event, together.
     *
     * <p>Accounts are recorded from the till's point of view — for a deposit the money comes from
     * the till, for a withdrawal it goes to it — so the recovery worker can rebuild the identical
     * posting without knowing anything the saga does not carry.
     */
    @Transactional
    /**
     * @param productCode the product resolved from the account the money moves through — not the
     *     one the caller named. A cash request's own {@code productCode} is accepted and ignored,
     *     so persisting that would record a blank, and the saga would say a transaction was priced
     *     by "version 1" without saying version 1 of what.
     */
    public UUID openCash(
            org.elyonar.fincore.core.orchestration.api.CashCommand command,
            ProductDecision decision,
            TillRecords.Till till,
            String kycTier,
            String productCode,
            String windowKey) {
        scopeTo(command.tenantId());

        boolean deposit =
                command.operation()
                        == org.elyonar.fincore.core.orchestration.api.CashCommand.Operation.DEPOSIT;
        UUID from = deposit ? till.ledgerAccountId() : command.customerAccountId();
        UUID to = deposit ? command.customerAccountId() : till.ledgerAccountId();

        UUID sagaId =
                jdbc.queryForObject(
                        """
                        INSERT INTO orchestration.sagas
                            (tenant_id, type, state, channel_idempotency_key, request_fingerprint,
                             subject_customer_id, product_code, product_version, decision, amount_minor,
                             fee_minor, currency, initiated_by, executed_by,
                             from_account_id, to_account_id, fee_account_id, till_id)
                        VALUES (?, ?, 'POSTING', ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """,
                        UUID.class,
                        command.tenantId(),
                        command.operation().name(),
                        command.idempotencyKey(),
                        command.fingerprint(),
                        command.customerId(),
                        productCode,
                        decision.productVersion(),
                        decisionSnapshot(decision, command.channel(), kycTier),
                        command.amountMinor(),
                        decision.feeMinor(),
                        command.currency(),
                        command.initiatedBy(),
                        command.executedBy(),
                        from,
                        to,
                        resolvedFeeAccount(decision.feeAccountId(), command.feeAccountId()),
                        till.id());

        jdbc.update(
                """
                INSERT INTO orchestration.limit_reservations
                    (tenant_id, saga_id, subject_id, limit_type, window_key, amount_minor,
                     currency, expires_at)
                VALUES (?, ?, ?, 'DAILY', ?, ?, ?, now() + INTERVAL '1 day')
                """,
                command.tenantId(),
                sagaId,
                command.customerId(),
                windowKey,
                command.amountMinor() + decision.feeMinor(),
                command.currency());

        enforceDailyLimit(command.tenantId(), command.customerId(), windowKey, decision.dailyLimitMinor());

        outbox.append(
                command.tenantId(),
                deposit ? "cash.deposit_initiated" : "cash.withdrawal_initiated",
                sagaId,
                payload(sagaId, command.amountMinor(), decision.feeMinor(), command.currency()));

        return sagaId;
    }

    /** Phase C, success: the saga completes and its reservation is consumed. */
    @Transactional
    public TransferResult complete(UUID tenantId, UUID sagaId, UUID ledgerTransactionId) {
        scopeTo(tenantId);
        jdbc.update(
                """
                UPDATE orchestration.sagas
                   SET state = 'COMPLETED', ledger_transaction_id = ?, terminal_at = now(),
                       claimed_by = NULL, claim_expires_at = NULL
                 WHERE id = ?
                """,
                ledgerTransactionId,
                sagaId);
        jdbc.update(
                "UPDATE orchestration.limit_reservations SET status = 'CONSUMED', resolved_at = now()"
                        + " WHERE saga_id = ?",
                sagaId);
        TransferResult result = readScoped(sagaId);
        outbox.append(
                tenantId,
                "transfer.completed",
                sagaId,
                payload(sagaId, result.amountMinor(), result.feeMinor(), result.currency()));
        return result;
    }

    /**
     * Phase C, definite failure: the saga fails and its reservation is released.
     *
     * <p>Released only here. Releasing on an unknown would free limit headroom for a transfer that
     * may already have posted.
     */
    @Transactional
    public void fail(UUID tenantId, UUID sagaId, String errorCode) {
        scopeTo(tenantId);
        jdbc.update(
                """
                UPDATE orchestration.sagas
                   SET state = 'FAILED', last_error = ?, terminal_at = now(),
                       claimed_by = NULL, claim_expires_at = NULL
                 WHERE id = ?
                """,
                errorCode,
                sagaId);
        jdbc.update(
                "UPDATE orchestration.limit_reservations SET status = 'RELEASED', resolved_at = now()"
                        + " WHERE saga_id = ?",
                sagaId);
        TransferResult result = readScoped(sagaId);
        outbox.append(
                tenantId,
                "transfer.failed",
                sagaId,
                payload(sagaId, result.amountMinor(), result.feeMinor(), result.currency()));
    }

    /**
     * Phase C, unknown: the attempt is logged and nothing else moves.
     *
     * <p>The saga stays POSTING and claimable, the reservation is untouched, and no event is
     * emitted — there is nothing yet to say happened.
     */
    @Transactional
    public void recordUnknownAttempt(UUID tenantId, UUID sagaId, String reason) {
        scopeTo(tenantId);
        jdbc.update(
                """
                INSERT INTO orchestration.saga_attempts (tenant_id, saga_id, attempt_no, outcome, detail)
                SELECT tenant_id, id, attempts + 1, 'UNKNOWN', ? FROM orchestration.sagas WHERE id = ?
                """,
                reason,
                sagaId);
        jdbc.update(
                "UPDATE orchestration.sagas SET attempts = attempts + 1,"
                        + " next_attempt_at = now() + INTERVAL '1 second' WHERE id = ?",
                sagaId);
    }

    /**
     * The references for a set of ledger transactions, as a map.
     *
     * <p>A statement is the Ledger's document and names its own transactions; a customer holds a
     * receipt that names ours. Without this the two cannot be joined, so a statement line and the
     * reference a customer reads down the telephone are unrelated strings — and the counter cannot
     * find the line they are asking about.
     *
     * <p>One query for a whole page rather than one per line: a hundred-line statement is a hundred
     * round trips otherwise, on a read somebody waits for.
     */
    @Transactional(readOnly = true)
    public java.util.Map<UUID, String> referencesForLedgerTransactions(
            UUID tenantId, java.util.Collection<UUID> ledgerTransactionIds) {
        if (ledgerTransactionIds.isEmpty()) {
            return java.util.Map.of();
        }
        scopeTo(tenantId);
        // A placeholder per id rather than an array parameter: array binding depends on the driver
        // mapping UUID[] to uuid[], and this is a read on the customer-facing path — it should work
        // the same way the rest of this class's SQL does.
        String placeholders = String.join(",", java.util.Collections.nCopies(ledgerTransactionIds.size(), "?"));
        var references = new java.util.HashMap<UUID, String>();
        org.springframework.jdbc.core.RowCallbackHandler collect =
                rs -> references.put(rs.getObject(1, UUID.class), rs.getString(2));
        jdbc.query(
                "SELECT ledger_transaction_id, reference FROM orchestration.sagas"
                        + " WHERE ledger_transaction_id IN ("
                        + placeholders
                        + ")",
                collect,
                ledgerTransactionIds.toArray());
        return references;
    }

    /** The saga as a caller sees it. */
    @Transactional(readOnly = true)
    public TransferResult read(UUID tenantId, UUID sagaId) {
        scopeTo(tenantId);
        return readScoped(sagaId);
    }

    /**
     * The same saga, named either way.
     *
     * <p>A caller holds one of two things: the {@code transactionId} another system stored, or the
     * {@code reference} printed on a receipt and read out over the telephone. Both name exactly one
     * posting, so both resolve here rather than forcing a channel to know which kind of string it
     * is holding before it can ask. Anything that does not parse as a UUID is tried as a reference —
     * references are unique per tenant and cannot collide with UUID text.
     */
    @Transactional(readOnly = true)
    public TransferResult find(UUID tenantId, String handle) {
        scopeTo(tenantId);
        String trimmed = handle == null ? "" : handle.trim();
        UUID asId;
        try {
            asId = UUID.fromString(trimmed);
        } catch (IllegalArgumentException notAUuid) {
            // References are stored upper-case, and somebody reading one off a receipt into a
            // search box should not have to reproduce that.
            return first(BY_REFERENCE, trimmed.toUpperCase());
        }
        return first(BY_ID, asId);
    }

    private TransferResult first(String sql, Object argument) {
        return jdbc.query(sql, SAGA_AS_RESULT, argument).stream().findFirst().orElse(null);
    }

    private static final String SAGA_COLUMNS =
            """
            SELECT id, reference, state, amount_minor, fee_minor, currency, product_version,
                   ledger_transaction_id, from_account_id, to_account_id
              FROM orchestration.sagas
            """;

    private static final String BY_ID = SAGA_COLUMNS + " WHERE id = ?";

    private static final String BY_REFERENCE = SAGA_COLUMNS + " WHERE reference = ?";

    /**
     * One posting in full, for somebody investigating it rather than posting it.
     *
     * <p>The self-joins answer the two questions a reversal raises and the row alone cannot: what
     * did this undo, and has this already been undone. Both are left joins because most postings
     * are neither, and both are resolved here rather than by the caller making two more round trips
     * to discover that the answer is usually "nothing".
     *
     * <p>{@code channel} comes out of the decision snapshot, which is the record of what was decided
     * at the time — not today's configuration. Nothing else from that snapshot is exposed; see
     * {@link TransactionDetail} for why.
     */
    @Transactional(readOnly = true)
    public TransactionDetail detail(UUID tenantId, String handle) {
        scopeTo(tenantId);
        String trimmed = handle == null ? "" : handle.trim();
        try {
            return firstDetail(DETAIL_BY_ID, UUID.fromString(trimmed));
        } catch (IllegalArgumentException notAUuid) {
            return firstDetail(DETAIL_BY_REFERENCE, trimmed.toUpperCase());
        }
    }

    private TransactionDetail firstDetail(String sql, Object argument) {
        return jdbc.query(sql, DETAIL_AS_RESULT, argument).stream().findFirst().orElse(null);
    }

    private static final String DETAIL_COLUMNS =
            """
            SELECT s.id, s.reference, s.type, s.state, s.amount_minor, s.fee_minor, s.currency,
                   s.decision ->> 'channel' AS channel, s.product_code, s.product_version,
                   s.subject_customer_id, s.from_account_id, s.to_account_id, s.fee_account_id,
                   s.till_id, s.ledger_transaction_id, s.initiated_by, s.created_at, s.terminal_at,
                   s.reverses_saga_id, undone.reference AS reverses_reference,
                   undoing.id AS reversed_by_id, undoing.reference AS reversed_by_reference,
                   s.approval_id
              FROM orchestration.sagas s
              LEFT JOIN orchestration.sagas undone  ON undone.id = s.reverses_saga_id
              LEFT JOIN orchestration.sagas undoing ON undoing.reverses_saga_id = s.id
                                                  AND undoing.state = 'COMPLETED'
            """;

    private static final String DETAIL_BY_ID = DETAIL_COLUMNS + " WHERE s.id = ?";

    private static final String DETAIL_BY_REFERENCE = DETAIL_COLUMNS + " WHERE s.reference = ?";

    private static final org.springframework.jdbc.core.RowMapper<TransactionDetail>
            DETAIL_AS_RESULT =
                    (rs, row) ->
                            new TransactionDetail(
                                    rs.getObject("id", UUID.class),
                                    rs.getString("reference"),
                                    rs.getString("type"),
                                    rs.getString("state"),
                                    rs.getLong("amount_minor"),
                                    rs.getLong("fee_minor"),
                                    rs.getString("currency"),
                                    rs.getString("channel"),
                                    rs.getString("product_code"),
                                    rs.getObject("product_version", Integer.class),
                                    rs.getObject("subject_customer_id", UUID.class),
                                    rs.getObject("from_account_id", UUID.class),
                                    rs.getObject("to_account_id", UUID.class),
                                    rs.getObject("fee_account_id", UUID.class),
                                    rs.getObject("till_id", UUID.class),
                                    rs.getObject("ledger_transaction_id", UUID.class),
                                    rs.getString("initiated_by"),
                                    rs.getObject("created_at", java.time.OffsetDateTime.class),
                                    rs.getObject("terminal_at", java.time.OffsetDateTime.class),
                                    rs.getObject("reverses_saga_id", UUID.class),
                                    rs.getString("reverses_reference"),
                                    rs.getObject("reversed_by_id", UUID.class),
                                    rs.getString("reversed_by_reference"),
                                    rs.getObject("approval_id", UUID.class));

    /**
     * The same read, for callers already inside a scoped transaction.
     *
     * <p>Separate because the tenant context is {@code SET LOCAL}: it belongs to the transaction,
     * so a method that re-scopes would be wrong to call from within one and a method that does not
     * would be wrong to call from outside. Naming both makes the requirement visible instead of
     * leaving it to be discovered when row-level security hides a row from its own writer.
     */
    private TransferResult readScoped(UUID sagaId) {
        return jdbc.queryForObject(
                """
                SELECT id, reference, state, amount_minor, fee_minor, currency, product_version,
                       ledger_transaction_id, from_account_id, to_account_id
                  FROM orchestration.sagas WHERE id = ?
                """,
                SAGA_AS_RESULT,
                sagaId);
    }

    /**
     * Loads everything a worker needs to finish a saga it did not start.
     *
     * <p>Read as the worker's role, which sees across tenants — a worker has no tenant context of
     * its own, because it serves all of them.
     */
    @Transactional(transactionManager = CoreProperties.Beans.WORKER_TX, readOnly = true)
    public Pending loadPending(UUID sagaId) {
        // The self-join resolves what a REVERSAL saga needs and its own row deliberately does not
        // carry: the ledger transaction its target posted. A reversal names no accounts — it names
        // a transaction — so the worker re-drives it via `ledger.reverse`, and this is where the
        // target comes from.
        return workerJdbc.query(
                """
                SELECT s.tenant_id, s.type, s.from_account_id, s.to_account_id, s.fee_account_id,
                       s.amount_minor, s.fee_minor, s.currency, s.initiated_by, s.attempts,
                       EXTRACT(EPOCH FROM (now() - s.created_at))::bigint AS age_seconds,
                       original.ledger_transaction_id AS reverses_ledger_transaction_id
                  FROM orchestration.sagas s
                  LEFT JOIN orchestration.sagas original ON original.id = s.reverses_saga_id
                 WHERE s.id = ? AND s.state IN ('RECEIVED', 'POSTING')
                """,
                rs ->
                        rs.next()
                                ? new Pending(
                                        rs.getObject("tenant_id", UUID.class),
                                        rs.getString("type"),
                                        rs.getObject("from_account_id", UUID.class),
                                        rs.getObject("to_account_id", UUID.class),
                                        rs.getObject("fee_account_id", UUID.class),
                                        rs.getLong("amount_minor"),
                                        rs.getLong("fee_minor"),
                                        rs.getString("currency"),
                                        rs.getString("initiated_by"),
                                        rs.getInt("attempts"),
                                        java.time.Duration.ofSeconds(rs.getLong("age_seconds")),
                                        rs.getObject("reverses_ledger_transaction_id", UUID.class))
                                : null,
                sagaId);
    }

    /**
     * Parks a saga a human must now determine, and opens the case.
     *
     * <p>Nothing is compensated and the reservation stays held: the money may have moved, and
     * releasing the headroom would let a second transfer breach the limit if it did.
     */
    @Transactional(transactionManager = CoreProperties.Beans.WORKER_TX)
    public void escalate(UUID tenantId, UUID sagaId) {
        workerJdbc.update(
                "UPDATE orchestration.sagas SET state = 'PENDING_RESOLUTION',"
                        + " claimed_by = NULL, claim_expires_at = NULL WHERE id = ?",
                sagaId);
        workerJdbc.update(
                "INSERT INTO orchestration.ops_cases (tenant_id, saga_id, kind)"
                        + " VALUES (?, ?, 'UNRESOLVED_OUTCOME')",
                tenantId,
                sagaId);
    }

    /**
     * Loads a transaction if — and only if — it can be reversed.
     *
     * <p>The conditions are in the query rather than in the caller: COMPLETED, not itself a
     * reversal, and not already reversed. A caller that checked these separately would leave a
     * window between the check and the reversal in which another request could reverse it too.
     */
    @Transactional(readOnly = true)
    public Reversible loadReversible(UUID tenantId, UUID sagaId) {
        scopeTo(tenantId);
        return jdbc.query(
                """
                SELECT id, amount_minor, fee_minor, currency, ledger_transaction_id
                  FROM orchestration.sagas s
                 WHERE s.id = ?
                   AND s.state = 'COMPLETED'
                   AND s.type <> 'REVERSAL'
                   AND NOT EXISTS (
                        SELECT 1 FROM orchestration.sagas r
                         WHERE r.reverses_saga_id = s.id AND r.state <> 'FAILED')
                """,
                rs ->
                        rs.next()
                                ? new Reversible(
                                        rs.getObject("id", UUID.class),
                                        rs.getLong("amount_minor"),
                                        rs.getLong("fee_minor"),
                                        rs.getString("currency"),
                                        rs.getObject("ledger_transaction_id", UUID.class))
                                : null,
                sagaId);
    }

    /**
     * Opens the reversal saga.
     *
     * <p>A new row rather than a mutation of the original: terminal states stay terminal and the
     * audit trail stays additive. The link is what ties them together.
     */
    @Transactional
    public UUID openReversal(
            UUID tenantId,
            UUID originalSagaId,
            UUID approvalId,
            String idempotencyKey,
            Reversible original,
            String initiatedBy) {
        scopeTo(tenantId);
        UUID reversalId =
                jdbc.queryForObject(
                        """
                        INSERT INTO orchestration.sagas
                            (tenant_id, type, state, channel_idempotency_key, request_fingerprint,
                             amount_minor, fee_minor, currency, initiated_by, executed_by,
                             reverses_saga_id, approval_id)
                        VALUES (?, 'REVERSAL', 'POSTING', ?, ?, ?, ?, ?, ?, 'core', ?, ?)
                        RETURNING id
                        """,
                        UUID.class,
                        tenantId,
                        idempotencyKey,
                        "reversal:" + originalSagaId,
                        original.amountMinor(),
                        original.feeMinor(),
                        original.currency(),
                        initiatedBy,
                        originalSagaId,
                        approvalId);

        outbox.append(
                tenantId,
                "transfer.reversal_initiated",
                reversalId,
                payload(reversalId, original.amountMinor(), original.feeMinor(), original.currency()));
        return reversalId;
    }

    /** A saga found by its idempotency key, with the fingerprint that decides replay vs 409. */
    public record Existing(String fingerprint, TransferResult result) {}

    /**
     * The saga cannot be turned into a posting at all.
     *
     * <p>Distinct from an unknown outcome: an unknown is retried because the answer may arrive,
     * whereas this will fail identically forever. Retrying it is not caution, it is a loop.
     */
    public static class Unretryable extends RuntimeException {
        public Unretryable(String message) {
            super(message);
        }
    }

    /** A completed transaction that may still be reversed, and what reversing it would undo. */
    public record Reversible(
            UUID id, long amountMinor, long feeMinor, String currency, UUID ledgerTransactionId) {}

    /**
     * Everything needed to re-drive a saga's outbound step exactly as it was first sent.
     *
     * @param reversesLedgerTransactionId for a REVERSAL saga, the ledger transaction its target
     *     posted — what {@code ledger.reverse} is aimed at. Null for every other type, and null for
     *     a reversal whose target never recorded one, which is unresolvable and escalates.
     */
    public record Pending(
            UUID tenantId,
            String type,
            UUID fromAccountId,
            UUID toAccountId,
            UUID feeAccountId,
            long amountMinor,
            long feeMinor,
            String currency,
            String initiatedBy,
            int attempts,
            java.time.Duration age,
            UUID reversesLedgerTransactionId) {

        /** The saga type that is re-driven via {@code ledger.reverse}, never rebuilt as a posting. */
        private static final String REVERSAL = "REVERSAL";

        public boolean reversal() {
            return REVERSAL.equals(type);
        }

        /**
         * The identical posting, under the given key.
         *
         * <p>Identical is the operative word: a retry that rebuilt a different posting would be
         * rejected as {@code IDEMPOTENCY_KEY_REUSED}, turning a recoverable unknown into a
         * permanent one.
         */
        public org.elyonar.fincore.core.orchestration.api.LedgerPosting postingUnder(String key) {
            if (reversal()) {
                // A reversal targets a transaction, not a pair of accounts — its saga row
                // deliberately names none, so "rebuilding" it fabricates entries with no accounts.
                // The zero-fee case used to slip past every guard and NPE inside the client,
                // outside every catch that could have scheduled a retry: the saga looped on lease
                // expiry forever. Unretryable, because no retry of this construction can ever work.
                throw new Unretryable("a REVERSAL saga is re-driven via ledger.reverse, never rebuilt as a posting");
            }
            var entries =
                    Postings.entriesFor(
                            type, fromAccountId, toAccountId, feeAccountId, amountMinor, feeMinor, currency);
            return new org.elyonar.fincore.core.orchestration.api.LedgerPosting(
                    key, initiatedBy, "retry", entries);
        }
    }
}
