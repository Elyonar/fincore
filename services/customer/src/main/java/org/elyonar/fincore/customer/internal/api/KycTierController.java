package org.elyonar.fincore.customer.internal.api;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.elyonar.fincore.auth.Authorization;
import org.elyonar.fincore.customer.api.CustomerBeans;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The KYC tiers this institution recognises.
 *
 * <p>A tier is a regulator's idea rather than the platform's. Nigeria's CBN defines three, with
 * particular documentary requirements and ceilings; Kenya, Ghana and the CFA zone define their own,
 * differently named and differently counted. `TIER_1..3` in code is a platform that is wrong for
 * every institution outside one jurisdiction.
 *
 * <p>It also closes a split that was already there. `customers.kyc_tier` has always been free text,
 * while the product service's limit rules carried a CHECK naming exactly three values — so an
 * institution could put somebody on `TIER_4` and then be unable to price it. The permissive half
 * was the customer half, which is the worse way round: the tier that could not be priced was the
 * one already assigned to a person, and every transaction under it was refused by an evaluator that
 * denies by default.
 *
 * <p>Reads are open to anyone who may see a customer, because every screen showing or setting a
 * tier needs the list. Writing is `customers:tier` — the same permission that changes somebody's
 * tier, because defining the ladder and moving people up it are the same authority.
 */
@Tag(name = "KYC tiers", description = "The tiers this institution recognises")
@RestController
@RequestMapping("/v1/kyc-tiers")
public class KycTierController {

    private final JdbcTemplate jdbc;

    public KycTierController(@Qualifier(CustomerBeans.JDBC) JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    @Transactional(readOnly = true, transactionManager = CustomerBeans.TRANSACTION_MANAGER)
    public List<Tier> list() {
        var identity = Authorization.require("customers:read");
        scopeTo(identity.tenantId());
        return jdbc.query(
                """
                SELECT code, name, requires, rank, active
                  FROM customer.kyc_tiers
                 ORDER BY active DESC, rank, code
                """,
                (rs, i) ->
                        new Tier(
                                rs.getString("code"),
                                rs.getString("name"),
                                rs.getString("requires"),
                                rs.getInt("rank"),
                                rs.getBoolean("active")));
    }

    /**
     * Defines a tier, or brings a retired one back.
     *
     * <p>Upsert, and retiring deactivates rather than deletes: people already on a tier keep it
     * until they are reviewed, and a limit rule written against it must keep pricing them. Deleting
     * the row would leave both pointing at nothing.
     */
    @PostMapping
    @Transactional(transactionManager = CustomerBeans.TRANSACTION_MANAGER)
    public Tier define(@RequestBody DefineTier request) {
        var identity = Authorization.require("customers:tier");
        scopeTo(identity.tenantId());

        String code = request.code() == null ? "" : request.code().trim().toUpperCase();
        if (!code.matches("^[A-Z0-9_]{2,32}$")) {
            throw new IllegalArgumentException(
                    "code must be 2 to 32 characters of A-Z, 0-9 or underscore");
        }

        jdbc.update(
                """
                INSERT INTO customer.kyc_tiers (tenant_id, code, name, requires, rank, active)
                VALUES (?,?,?,?,?, TRUE)
                ON CONFLICT (tenant_id, code) DO UPDATE
                   SET name = EXCLUDED.name,
                       requires = EXCLUDED.requires,
                       rank = EXCLUDED.rank,
                       active = TRUE
                """,
                identity.tenantId(),
                code,
                request.name() == null || request.name().isBlank() ? code : request.name().trim(),
                request.requires(),
                request.rank() == null ? 0 : request.rank());

        return new Tier(code, request.name(), request.requires(), request.rank() == null ? 0 : request.rank(), true);
    }

    private void scopeTo(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    /**
     * @param requires what the institution must hold before assigning it — prose, never evaluated
     * @param rank the order tiers escalate in, so a screen lists a ladder rather than an alphabet
     */
    public record Tier(String code, String name, String requires, int rank, boolean active) {}

    /**
     * The request, which carries no {@code active}.
     *
     * <p>Defining a tier activates it — there is no such thing as defining one that is already
     * retired. Reusing the response record here meant a body omitting the field was a null mapped
     * onto a primitive, which Jackson refuses outright: every caller got a 400 with no clue which
     * field it meant. {@code rank} is boxed for the same reason, and defaults rather than refusing.
     */
    public record DefineTier(String code, String name, String requires, Integer rank) {}
}
