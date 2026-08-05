package org.elyonar.fincore.ledger.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.elyonar.fincore.ledger.support.LedgerPostgresTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Asserts that every object the design relies on actually exists in the migrated schema.
 *
 * <p>This is the half of the schema suite that catches a migration silently dropping a trigger,
 * an index or a policy — the objects where this ledger's correctness lives. Existence is
 * necessary but not sufficient, so {@link SchemaEnforcementTest} separately proves each one
 * <em>fires</em>. A trigger that exists and does nothing is worse than no trigger, because it
 * reads as protection.
 */
@DisplayName("schema presence — every object the design depends on exists")
class SchemaPresenceTest extends LedgerPostgresTest {

    @Autowired JdbcTemplate jdbc;

    @ParameterizedTest(name = "table {0} exists")
    @ValueSource(
            strings = {
                "currencies",
                "tenant_config",
                "accounts",
                "balances",
                "accounting_periods",
                "ledger_transactions",
                "entries",
                "holds",
                "outbox_events"
            })
    void table_exists(String table) {
        assertThat(exists("SELECT 1 FROM information_schema.tables "
                        + "WHERE table_schema='public' AND table_name=?", table))
                .as("table %s is one of the nine in data-model.md", table)
                .isTrue();
    }

    @Test
    void exactly_nine_tables() {
        List<String> tables =
                jdbc.queryForList(
                        "SELECT table_name FROM information_schema.tables "
                                + "WHERE table_schema='public' AND table_type='BASE TABLE' "
                                + "AND table_name <> 'flyway_schema_history' ORDER BY table_name",
                        String.class);
        assertThat(tables)
                .as("data-model.md says nine tables; an unlisted table is either an undocumented "
                        + "design change or a leftover")
                .hasSize(9);
    }

    @ParameterizedTest(name = "trigger {0} exists")
    @ValueSource(
            strings = {
                "entries_are_append_only",
                "accounts_identity_is_immutable",
                "ledger_transactions_no_reversal_of_reversal",
                "currencies_exponent_is_immutable_in_use",
                "entries_currency_matches_account",
                "holds_currency_matches_account",
                "holds_terminal_states_are_terminal",
                "accounting_periods_never_reopen",
                "tenant_config_is_append_only"
            })
    void trigger_exists(String trigger) {
        assertThat(exists("SELECT 1 FROM pg_trigger WHERE tgname=? AND NOT tgisinternal", trigger))
                .as("trigger %s enforces a rule the docs call schema-enforced", trigger)
                .isTrue();
    }

    @ParameterizedTest(name = "index {0} exists")
    @ValueSource(
            strings = {
                "ledger_transactions_one_reversal_per_original",
                "ledger_transactions_compensations",
                "outbox_pending",
                "entries_statement",
                "entries_by_transaction",
                "holds_active_by_account",
                "holds_expiry_sweep",
                "accounts_by_tenant_group"
            })
    void index_exists(String index) {
        assertThat(exists("SELECT 1 FROM pg_indexes WHERE schemaname='public' AND indexname=?", index))
                .as("index %s backs a documented access path or uniqueness rule", index)
                .isTrue();
    }

    @ParameterizedTest(name = "RLS enabled on {0}")
    @ValueSource(
            strings = {
                "accounts",
                "balances",
                "ledger_transactions",
                "entries",
                "holds",
                "accounting_periods",
                "tenant_config",
                "outbox_events"
            })
    void row_level_security_is_enabled(String table) {
        assertThat(exists(
                        "SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                                + "WHERE n.nspname='public' AND c.relname=? AND c.relrowsecurity",
                        table))
                .as("RLS is the backstop for a query that forgot its tenant; %s carries tenant data",
                        table)
                .isTrue();

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pg_policies WHERE schemaname='public' AND tablename=?",
                        Integer.class,
                        table))
                .as("RLS enabled without a policy denies everything, which fails closed but " + "silently")
                .isGreaterThan(0);
    }

    @ParameterizedTest(name = "composite (tenant_id, id) FK target on {0}")
    @ValueSource(strings = {"accounts", "ledger_transactions"})
    void composite_unique_targets_exist(String table) {
        assertThat(exists(
                        """
                        SELECT 1
                          FROM pg_constraint c
                          JOIN pg_class t ON t.oid = c.conrelid
                         WHERE t.relname = ?
                           AND c.contype = 'u'
                           AND (SELECT array_agg(a.attname::text ORDER BY a.attname::text)
                                  FROM unnest(c.conkey) k
                                  JOIN pg_attribute a
                                    ON a.attrelid = c.conrelid AND a.attnum = k)
                               = ARRAY['id','tenant_id']::text[]
                        """,
                        table))
                .as("cross-tenant references are meant to be structurally impossible, which "
                        + "requires a (tenant_id, id) target for child FKs")
                .isTrue();
    }

    private boolean exists(String sql, Object... args) {
        Integer found = jdbc.query(sql, rs -> rs.next() ? 1 : 0, args);
        return found != null && found == 1;
    }
}
