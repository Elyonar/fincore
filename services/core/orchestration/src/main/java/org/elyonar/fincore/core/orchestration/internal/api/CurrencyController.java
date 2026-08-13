package org.elyonar.fincore.core.orchestration.internal.api;

import io.swagger.v3.oas.annotations.tags.Tag;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.auth.Authorization;
import org.elyonar.fincore.core.orchestration.api.CoreProperties;
import org.elyonar.fincore.core.orchestration.internal.ledger.LedgerClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The currencies this institution deals in.
 *
 * <p>Two jobs, and the second is the one that mattered. It gives every currency field a list to
 * choose from instead of a text box somebody types `ngn` into. And it carries each currency's
 * <em>exponent</em> — the number of decimal places ISO 4217 assigns it — which the portal had
 * hardcoded to 2. That is right for the naira and wrong for the yen, and a ¥2,500 balance was
 * rendering as ¥25.00.
 *
 * <p><strong>An offering, not an allow-list.</strong> No transaction is validated against this, and
 * none should be: the ledger refuses a currency it has never heard of and that is the authority. A
 * row here says "offer this, and render it this way".
 *
 * <p>Which is why adding one is checked against that authority rather than trusted. The exponent is
 * copied from the ledger's registry instead of accepted from the request, and a code the registry
 * does not carry is refused outright. Not a second gate on the money path — a refusal to put
 * something on a form that could never have worked. The alternative was an institution adding a
 * currency, offering it everywhere, and discovering at a counter that no account can be opened in
 * it.
 *
 * <p>Reads are open to anyone who may see the institution, because every screen that shows money
 * needs the exponent. Writing is `org:manage` — which currencies an institution offers is a
 * decision about the institution, not about a transaction.
 */
@Tag(name = "Currencies", description = "What this institution deals in, and how to render it")
@RestController
@RequestMapping("/v1/currencies")
public class CurrencyController {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final LedgerClient ledger;

    public CurrencyController(
            @Qualifier(CoreProperties.Beans.ORCHESTRATION_JDBC) JdbcTemplate jdbc, LedgerClient ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    @GetMapping
    @Transactional(readOnly = true, transactionManager = CoreProperties.Beans.ORCHESTRATION_TX)
    public List<Currency> list() {
        var identity = Authorization.require("org:read");
        scopeTo(identity.tenantId());
        return jdbc.query(
                "SELECT code, name, exponent, active FROM platform.currencies ORDER BY active DESC, code",
                (rs, i) ->
                        new Currency(
                                rs.getString("code"),
                                rs.getString("name"),
                                rs.getInt("exponent"),
                                rs.getBoolean("active")));
    }

    /**
     * Adds a currency, or brings a withdrawn one back.
     *
     * <p>Upsert rather than insert: withdrawing a currency deactivates the row instead of deleting
     * it, because accounts opened in it still exist and still have to render. Offering it again is
     * the same row, active once more — a second row with the same code could carry a different
     * exponent, and then the same balance would render two ways depending on which was read.
     */
    @PostMapping
    @Transactional(transactionManager = CoreProperties.Beans.ORCHESTRATION_TX)
    public Currency add(@RequestBody Offer request) {
        var identity = Authorization.require("org:manage");
        scopeTo(identity.tenantId());

        String code = request.code() == null ? "" : request.code().trim().toUpperCase();
        if (!code.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("code must be a three-letter ISO 4217 code");
        }

        // The exponent is read from the ledger's registry, never taken from the request.
        //
        // It was accepted from the caller, and that was the flaw in this endpoint. The exponent is
        // not an opinion an institution holds — it is what makes an integer of minor units mean an
        // amount, the ledger enforces it as immutable once a currency is in use, and a second copy
        // that could disagree meant the same balance rendering two ways depending on which store
        // was read.
        //
        // Membership comes from the same read, and this is where the refusal belongs. The class
        // note above is right that the ledger is the authority and Core must not become a second
        // gate — but there is a difference between validating a transaction and refusing to offer
        // something that cannot work. Without this, an institution could add a currency, put it on
        // every form, and find out at the counter, with a customer waiting.
        Registered registered = fromTheRegistry(identity.tenantId(), code);

        jdbc.update(
                """
                INSERT INTO platform.currencies (tenant_id, code, name, exponent, active)
                VALUES (?,?,?,?, TRUE)
                ON CONFLICT (tenant_id, code)
                DO UPDATE SET name = EXCLUDED.name, exponent = EXCLUDED.exponent, active = TRUE
                """,
                identity.tenantId(),
                code,
                // The institution may call it what its customers call it; only the exponent is
                // the ledger's to decide. Falling back to the registry's name beats falling back
                // to the bare code, which reads as a placeholder nobody got round to filling in.
                request.name() == null || request.name().isBlank() ? registered.name() : request.name().trim(),
                registered.exponent());

        return new Currency(code, request.name(), registered.exponent(), true);
    }

    /**
     * Everything the ledger will carry, so a settings screen can offer a choice.
     *
     * <p>Proxied rather than duplicated. Core could keep its own copy of ISO 4217 and would then
     * have two lists of decimal places that are only equal until one of them is edited. What an
     * institution offers is Core's to store; what exists is not.
     */
    @GetMapping("/registry")
    public List<Registered> registry() {
        var identity = Authorization.require("org:read");
        LedgerClient.RawRead read = ledger.get(identity.tenantId(), "/v1/currencies");
        if (read.unreachable() || read.status() != 200 || read.body() == null) {
            throw new IllegalStateException("the ledger's currency registry could not be read");
        }
        List<Registered> all = new java.util.ArrayList<>();
        for (JsonNode one : MAPPER.readTree(read.body())) {
            all.add(
                    new Registered(
                            one.path("code").asString(""),
                            one.path("name").asString(""),
                            one.path("exponent").asInt(2)));
        }
        return all;
    }

    /**
     * Withdraws a currency from what the institution offers.
     *
     * <p>Deactivates rather than deletes, and that is the whole point of the row. Accounts opened
     * in it still exist, still hold balances, and still have to render at the right number of
     * decimal places — deleting the row would leave every one of them formatted by a fallback.
     * Withdrawing stops it being offered on a form; it does not unmake what was opened.
     */
    @org.springframework.web.bind.annotation.DeleteMapping("/{code}")
    @Transactional(transactionManager = CoreProperties.Beans.ORCHESTRATION_TX)
    public void withdraw(@org.springframework.web.bind.annotation.PathVariable String code) {
        var identity = Authorization.require("org:manage");
        scopeTo(identity.tenantId());
        jdbc.update(
                "UPDATE platform.currencies SET active = FALSE WHERE tenant_id = ? AND code = ?",
                identity.tenantId(),
                code == null ? "" : code.trim().toUpperCase());
    }

    /**
     * The code as the ledger's registry has it, or a refusal.
     *
     * <p>Fails closed on an unreachable ledger. Guessing an exponent here would put a number into
     * a row that every screen afterwards trusts, and being wrong is money off by a factor of ten.
     */
    private Registered fromTheRegistry(UUID tenantId, String code) {
        // The registry is not tenant data — the exponent of the yen is the same for everybody —
        // but the client stamps the tenant header on every call and the ledger ignores it here.
        LedgerClient.RawRead read = ledger.get(tenantId, "/v1/currencies");
        if (read.unreachable() || read.status() != 200 || read.body() == null) {
            throw new IllegalStateException(
                    "the ledger's currency registry could not be read, so " + code + " cannot be offered yet");
        }
        try {
            for (JsonNode one : MAPPER.readTree(read.body())) {
                if (code.equals(one.path("code").asString(""))) {
                    return new Registered(code, one.path("name").asString(code), one.path("exponent").asInt(2));
                }
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("the ledger's currency registry could not be read", e);
        }
        throw new IllegalArgumentException(
                code + " is not in the ledger's currency registry, so no account could be opened in it");
    }

    /** A currency the ledger carries. Public because the registry read hands it to a caller. */
    public record Registered(String code, String name, int exponent) {}

    private void scopeTo(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    /**
     * @param exponent decimal places, per ISO 4217 — 2 for NGN, 0 for JPY, 3 for KWD
     * @param active false for one the institution has withdrawn but whose accounts still exist
     */
    public record Currency(String code, String name, int exponent, boolean active) {}

    /**
     * The request, which carries neither {@code exponent} nor {@code active}.
     *
     * <p>Not tidiness. Reusing the response record meant a body that sensibly omitted both — the
     * exponent is the ledger's to decide and offering a currency is what makes it active — was two
     * nulls mapped onto primitives, which Jackson refuses outright with a 400 naming no field. The
     * caller was right and the contract was wrong.
     */
    public record Offer(String code, String name) {}
}
