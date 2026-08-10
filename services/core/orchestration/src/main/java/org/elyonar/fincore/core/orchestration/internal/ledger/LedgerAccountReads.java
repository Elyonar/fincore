package org.elyonar.fincore.core.orchestration.internal.ledger;

import java.util.UUID;
import org.elyonar.fincore.core.product.api.LedgerAccounts;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Orchestration answering Product's question about a ledger account.
 *
 * <p>Product declares {@link LedgerAccounts} because it needs the answer and implements nothing,
 * because it may not hold an HTTP client (AGENTS.md hard rule 3). Orchestration implements it
 * because it already holds the only ledger client on the platform and already depends on
 * {@code product.api}. No module edge is added in either direction — the dependency inverts, which
 * is the whole point of declaring the port on the consumer's side.
 *
 * <p>Read-only, and deliberately so. {@link LedgerClient#get} is GET-only by contract, so nothing
 * here can create an account even by accident.
 *
 * <p>Three answers, never two. A 5xx or an unreachable ledger is {@link Account.Unreadable} and
 * never {@link Account.Absent}: refusing a correctly-authored fee rule because the ledger was
 * restarting would be the read-side version of compensating an unknown outcome.
 */
@Component
public class LedgerAccountReads implements LedgerAccounts {

    private final LedgerClient ledger;
    private final JsonMapper json = JsonMapper.builder().build();

    public LedgerAccountReads(LedgerClient ledger) {
        this.ledger = ledger;
    }

    @Override
    public Account describe(UUID tenantId, UUID accountId) {
        if (accountId == null) {
            return new Account.Absent();
        }

        LedgerClient.RawRead read = ledger.get(tenantId, "/v1/accounts/" + accountId);

        if (read.unreachable()) {
            return new Account.Unreadable("ledger unreachable");
        }
        if (read.status() >= 500) {
            return new Account.Unreadable("ledger returned " + read.status());
        }
        if (read.status() == 404) {
            // The ledger answers another tenant's account with 404 too, deliberately. Product must
            // not try to tell the two apart, and does not need to: both mean "you may not name it".
            return new Account.Absent();
        }
        if (read.status() != 200) {
            // A 400 or a 401 here is a Core defect — a malformed path, or a service credential the
            // ledger will not take. Neither is a fact about the account, so neither is Absent.
            return new Account.Unreadable("ledger returned " + read.status());
        }

        try {
            JsonNode parsed = json.readTree(read.body());
            return new Account.Known(
                    parsed.path("type").asString(),
                    parsed.path("currency").asString(),
                    parsed.path("status").asString());
        } catch (RuntimeException e) {
            // A 200 we cannot read is not an account we can vouch for.
            return new Account.Unreadable("unreadable ledger response");
        }
    }
}
