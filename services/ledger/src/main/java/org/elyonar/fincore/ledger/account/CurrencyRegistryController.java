package org.elyonar.fincore.ledger.account;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The currencies this ledger carries, and how many decimal places each has.
 *
 * <p>The registry was readable only by the foreign key that enforced it: a caller could find out
 * whether a currency existed by opening an account in it and seeing what happened. That is a fine
 * way to learn and a poor way to configure — an institution choosing what to offer had to guess,
 * and discovered the answer at the moment a customer was standing in front of somebody.
 *
 * <p>Deliberately not tenant-scoped, and no {@code TenantHeader}. The exponent of the yen is not a
 * fact about a tenant, and every tenant's amounts in a currency mean the same thing — that is
 * precisely why the exponent is immutable once the currency is in use. Nothing here is anybody's
 * data; it is the vocabulary all of them are written in.
 *
 * <p>What an institution <em>offers</em> is a different question with a different answer, and lives
 * in Core. This is the list that one is drawn from.
 */
@RestController
@RequestMapping("/v1")
@Tag(name = "Currencies", description = "The currencies this ledger carries")
public class CurrencyRegistryController {

    private final JdbcTemplate jdbc;

    public CurrencyRegistryController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/currencies")
    @Operation(
            summary = "The currency registry",
            description =
                    "Every currency this ledger will accept an account, entry or hold in, with the"
                        + " ISO 4217 decimal places that give its integer minor units meaning. An"
                        + " institution's own list of what it offers is drawn from this one.")
    public List<Currency> list() {
        return jdbc.query(
                "SELECT code, minor_unit_exponent, display_name FROM currencies ORDER BY code",
                (rs, i) ->
                        // CHAR(3) pads; the trim is what stops "USD " reaching a caller that
                        // compares it against its own configured "USD".
                        new Currency(
                                rs.getString("code").trim(),
                                rs.getString("display_name"),
                                rs.getInt("minor_unit_exponent")));
    }

    /** @param exponent decimal places — 2 for NGN, 0 for JPY, 3 for KWD */
    public record Currency(String code, String name, int exponent) {}
}
