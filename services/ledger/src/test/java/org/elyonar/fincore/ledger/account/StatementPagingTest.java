package org.elyonar.fincore.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.elyonar.fincore.ledger.posting.EntryLine.Direction.CREDIT;
import static org.elyonar.fincore.ledger.posting.EntryLine.Direction.DEBIT;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.elyonar.fincore.ledger.posting.EntryLine;
import org.elyonar.fincore.ledger.posting.PostTransactionCommand;
import org.elyonar.fincore.ledger.posting.PostingService;
import org.elyonar.fincore.ledger.support.LedgerPostgresTest;
import org.elyonar.fincore.ledger.support.TenantSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Paging walks one period's lines. It is not a change feed, and the tests below pin the
 * distinction as much as the mechanics.
 */
@DisplayName("statement paging — a document in chunks, not a feed")
class StatementPagingTest extends LedgerPostgresTest {

    @Autowired PostingService posting;
    @Autowired StatementService statements;
    @Autowired DataSource dataSource;

    private UUID tenant;
    private UUID customer;
    private UUID settlement;
    private TenantSession db;
    private final ZoneId lagos = ZoneId.of("Africa/Lagos");

    @BeforeEach
    void seed() {
        tenant = UUID.randomUUID();
        customer = UUID.randomUUID();
        settlement = UUID.randomUUID();
        db = TenantSession.open(dataSource, tenant);
        db.execute("INSERT INTO currencies VALUES ('NGN',2,'Naira') ON CONFLICT (code) DO NOTHING");
        for (UUID id : List.of(customer, settlement)) {
            db.execute(
                    "INSERT INTO accounts (id, tenant_id, idempotency_key, type, currency, allow_negative)"
                            + " VALUES (?,?,?, 'CUSTOMER','NGN', true)",
                    id, tenant, "acct-" + id);
            db.execute("INSERT INTO balances (account_id, tenant_id) VALUES (?,?)", id, tenant);
        }
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private void post(String key, long amount, LocalDate valueDate) {
        posting.post(
                new PostTransactionCommand(
                        tenant, key, "u", "s", "d",
                        List.of(
                                new EntryLine(settlement, DEBIT, amount, "NGN", valueDate),
                                new EntryLine(customer, CREDIT, amount, "NGN", valueDate)),
                        null, null, valueDate == null ? null : "backfill"));
    }

    /** Walks every page and returns the lines in order. */
    private List<StatementService.Line> walkAllPages(LocalDate from, LocalDate to, int pageSize) {
        List<StatementService.Line> all = new ArrayList<>();
        String cursor = null;
        int guard = 0;
        do {
            var page = statements.forPeriod(tenant, customer, from, to, pageSize, cursor);
            all.addAll(page.lines());
            cursor = page.nextCursor();
            if (++guard > 100) {
                throw new IllegalStateException("paging did not terminate");
            }
        } while (cursor != null);
        return all;
    }

    @Test
    @DisplayName("pages cover every line exactly once, in business order")
    void pages_cover_everything_exactly_once() {
        LocalDate today = LocalDate.now(lagos);
        for (int i = 0; i < 25; i++) {
            post("tx-" + i, 100 + i, today);
        }

        var walked = walkAllPages(today, today, 7);

        assertThat(walked).hasSize(25);
        assertThat(walked.stream().map(StatementService.Line::entryId).distinct().count())
                .as("no line appears twice")
                .isEqualTo(25);
        assertThat(walked.stream().map(StatementService.Line::entryId).toList())
                .as("and the order is stable across pages")
                .isSorted();
    }

    @Test
    @DisplayName("the whole period reconciles when the pages are summed")
    void pages_reconcile_across_the_whole_period() {
        LocalDate today = LocalDate.now(lagos);
        for (int i = 0; i < 12; i++) {
            post("tx-" + i, 1_000, today);
        }

        var firstPage = statements.forPeriod(tenant, customer, today, today, 5, null);
        long movements = walkAllPages(today, today, 5).stream().mapToLong(l -> "CREDIT".equals(l.direction()) ? l.amountMinor() : -l.amountMinor()).sum();

        assertThat(firstPage.openingMinor() + movements)
                .as("opening + every page's movements = closing; the header is what makes this checkable")
                .isEqualTo(firstPage.closingMinor());
    }

    @Test
    @DisplayName("opening and closing describe the period, identically on every page")
    void header_is_constant_across_pages() {
        LocalDate today = LocalDate.now(lagos);
        for (int i = 0; i < 9; i++) {
            post("tx-" + i, 500, today);
        }

        var page1 = statements.forPeriod(tenant, customer, today, today, 4, null);
        var page2 = statements.forPeriod(tenant, customer, today, today, 4, page1.nextCursor());

        assertThat(page2.openingMinor())
                .as("a running total per page would make each page look like its own statement")
                .isEqualTo(page1.openingMinor());
        assertThat(page2.closingMinor()).isEqualTo(page1.closingMinor());
    }

    @Test
    @DisplayName("entries sharing a value date are not skipped — the cursor carries the full sort key")
    void cursor_handles_ties_on_value_date() {
        // Every line here has the same value_date, so a cursor of value_date alone would loop or
        // skip, and a cursor of id alone would disagree with the (value_date, id) ordering.
        LocalDate today = LocalDate.now(lagos);
        for (int i = 0; i < 10; i++) {
            post("tie-" + i, 250, today);
        }

        assertThat(walkAllPages(today, today, 3)).hasSize(10);
    }

    @Test
    @DisplayName("backdated lines page correctly alongside same-day ones")
    void mixed_value_dates_page_in_business_order() {
        LocalDate today = LocalDate.now(lagos);
        for (int i = 0; i < 6; i++) {
            post("today-" + i, 100, today);
        }
        for (int i = 0; i < 6; i++) {
            post("back-" + i, 100, today.minusDays(3));
        }

        var walked = walkAllPages(today.minusDays(5), today, 4);

        assertThat(walked).hasSize(12);
        assertThat(walked.stream().map(StatementService.Line::valueDate).toList())
                .as("business order across page boundaries")
                .isSorted();
    }

    @Test
    @DisplayName("the last page has no cursor")
    void final_page_has_no_cursor() {
        LocalDate today = LocalDate.now(lagos);
        post("tx-1", 100, today);

        var only = statements.forPeriod(tenant, customer, today, today, 50, null);

        assertThat(only.lines()).hasSize(1);
        assertThat(only.nextCursor()).as("the walk is spent").isNull();
    }

    @Test
    @DisplayName("page size is capped, however large a caller asks for")
    void page_size_is_capped() {
        LocalDate today = LocalDate.now(lagos);
        for (int i = 0; i < 5; i++) {
            post("tx-" + i, 100, today);
        }

        var page = statements.forPeriod(tenant, customer, today, today, 999_999, null);

        assertThat(page.lines()).hasSize(5);
        assertThat(StatementService.MAX_PAGE_SIZE)
                .as("an unbounded response is what this whole change exists to prevent")
                .isEqualTo(1000);
    }
}
