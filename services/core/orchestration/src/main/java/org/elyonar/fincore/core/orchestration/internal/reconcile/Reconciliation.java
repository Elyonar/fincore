package org.elyonar.fincore.core.orchestration.internal.reconcile;

import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.core.orchestration.api.CoreProperties;
import org.elyonar.fincore.core.orchestration.api.LedgerRead;
import org.elyonar.fincore.core.orchestration.internal.ledger.LedgerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Invariant 6, as running code: Core and the Ledger agree about every completed saga.
 *
 * <p>Scans sagas that reached {@code COMPLETED} inside the lookback window and asks the Ledger
 * for each recorded transaction. Two facts must hold: the transaction exists, and its debit total
 * equals the saga's principal plus fee. A violation is written to
 * {@code reconciliation_findings} (append-only, unique per saga and kind) and surfaced as an
 * {@code ops_cases} row of kind {@code RECONCILIATION_MISMATCH}, because a disagreement between
 * the business record and the monetary record is precisely what an operator queue is for.
 *
 * <p>What this deliberately does not do:
 *
 * <ul>
 *   <li><strong>It never mutates on a suspicion.</strong> An {@link LedgerRead.Unknown} answer
 *       records nothing — the ledger being unreachable during its maintenance window must not
 *       flag every saga in the scan as missing money. Ask again next run.
 *   <li><strong>It does not probe FAILED sagas.</strong> Detecting "the ledger posted but the
 *       saga says failed" needs a read-by-idempotency-key on the Ledger's API, which does not
 *       exist; re-posting the key to find out could <em>make</em> it true. That half of the
 *       invariant waits on a ledger amendment and is recorded in testing.md as such.
 * </ul>
 *
 * <p>Runs as the worker: like the saga worker it scans every tenant's rows, so it holds the
 * worker's policy rather than BYPASSRLS, and it never carries a tenant context of its own.
 */
@Service
public class Reconciliation {

    private static final Logger log = LoggerFactory.getLogger(Reconciliation.class);

    private final JdbcTemplate workerJdbc;
    private final TransactionTemplate workerTx;
    private final LedgerClient ledger;
    private final int lookbackHours;

    public Reconciliation(
            @Qualifier(CoreProperties.Beans.WORKER_JDBC) JdbcTemplate workerJdbcTemplate,
            @Qualifier(CoreProperties.Beans.WORKER_TX) PlatformTransactionManager workerTransactionManager,
            LedgerClient ledger,
            @Value("${" + CoreProperties.RECONCILIATION_LOOKBACK_HOURS + ":24}") int lookbackHours) {
        this.workerJdbc = workerJdbcTemplate;
        this.workerTx = new TransactionTemplate(workerTransactionManager);
        this.ledger = ledger;
        this.lookbackHours = lookbackHours;
    }

    /** One pass over the window. Returns how many findings were newly recorded. */
    public int run() {
        List<CompletedSaga> completed =
                workerJdbc.query(
                        """
                        SELECT id, tenant_id, type, ledger_transaction_id, amount_minor, fee_minor
                          FROM orchestration.sagas
                         WHERE state = 'COMPLETED'
                           AND terminal_at > now() - make_interval(hours => ?)
                         ORDER BY terminal_at
                        """,
                        (rs, i) ->
                                new CompletedSaga(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("tenant_id", UUID.class),
                                        rs.getString("type"),
                                        rs.getObject("ledger_transaction_id", UUID.class),
                                        rs.getLong("amount_minor"),
                                        rs.getLong("fee_minor")),
                        lookbackHours);

        int newFindings = 0;
        for (CompletedSaga saga : completed) {
            switch (ledger.read(saga.tenantId(), saga.ledgerTransactionId())) {
                case LedgerRead.NotFound ignored ->
                        newFindings +=
                                record(
                                        saga,
                                        "LEDGER_MISSING",
                                        "saga completed against ledger transaction "
                                                + saga.ledgerTransactionId()
                                                + ", which the ledger does not have");
                case LedgerRead.Found found -> {
                    long expected = expectedDebitsFor(saga);
                    if (found.totalDebitMinor() != expected) {
                        newFindings +=
                                record(
                                        saga,
                                        "AMOUNT_MISMATCH",
                                        "saga decided " + expected + " minor (principal "
                                                + saga.amountMinor() + " + fee " + saga.feeMinor()
                                                + "); ledger debits total " + found.totalDebitMinor());
                    }
                }
                case LedgerRead.Unknown unknown ->
                        // Say nothing on uncertainty; the next run asks again.
                        log.debug("reconciliation could not read {}: {}", saga.ledgerTransactionId(), unknown.reason());
            }
        }
        if (newFindings > 0) {
            log.warn("reconciliation recorded {} new finding(s); see orchestration.reconciliation_findings", newFindings);
        }
        return newFindings;
    }

    /**
     * Records a finding and its ops case, idempotently and <strong>atomically</strong> — the unique
     * index per (saga, kind) and the one-open-case-per-saga index arbitrate, so an unfixed mismatch
     * stays one finding and one case however many runs re-observe it.
     *
     * <p>An explicit {@link TransactionTemplate} rather than {@code @Transactional}, for the reason
     * Notification's intake spells out: an annotation only applies through the proxy, and this
     * method is called from {@link #run} on {@code this}. When it carried the annotation the two
     * inserts committed separately, so a crash between them left a finding the duplicate guard
     * would suppress on every later run — a mismatch recorded forever in a table no operator queue
     * ever surfaced. One transaction makes the pair a pair: both rows, or neither.
     */
    private int record(CompletedSaga saga, String kind, String detail) {
        Integer recorded =
                workerTx.execute(
                        status -> {
                            int inserted =
                                    workerJdbc.update(
                                            """
                                            INSERT INTO orchestration.reconciliation_findings (tenant_id, saga_id, kind, detail)
                                            VALUES (?,?,?,?)
                                            ON CONFLICT ON CONSTRAINT one_finding_per_saga_per_kind DO NOTHING
                                            """,
                                            saga.tenantId(),
                                            saga.id(),
                                            kind,
                                            detail);
                            if (inserted == 0) {
                                return 0;
                            }
                            workerJdbc.update(
                                    """
                                    INSERT INTO orchestration.ops_cases (tenant_id, saga_id, kind)
                                    VALUES (?, ?, 'RECONCILIATION_MISMATCH')
                                    ON CONFLICT DO NOTHING
                                    """,
                                    saga.tenantId(),
                                    saga.id());
                            return 1;
                        });
        return recorded == null ? 0 : recorded;
    }

    /**
     * What the debit side of this posting must total.
     *
     * <p>Not simply principal + fee, and assuming it was raised a mismatch against every deposit
     * that charged one. A deposit's fee comes out of the <em>credit</em> side — the till hands over
     * the gross notes and is debited once, while the customer and the fee account are credited
     * between them — so its debits total the principal alone. A withdrawal or a transfer debits the
     * customer for both, so theirs total principal + fee.
     *
     * <p>The consequence of getting this wrong was quiet and expensive: every fee-bearing deposit
     * opened an AMOUNT_MISMATCH case, in a queue no screen rendered. Two of them were sitting there
     * against perfectly correct postings before anybody could see the queue at all.
     */
    private static long expectedDebitsFor(CompletedSaga saga) {
        return "DEPOSIT".equals(saga.type()) ? saga.amountMinor() : saga.amountMinor() + saga.feeMinor();
    }

    record CompletedSaga(
            UUID id,
            UUID tenantId,
            String type,
            UUID ledgerTransactionId,
            long amountMinor,
            long feeMinor) {}
}
