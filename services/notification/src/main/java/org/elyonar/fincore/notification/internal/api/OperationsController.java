package org.elyonar.fincore.notification.internal.api;

import java.util.List;
import java.util.Map;
import org.elyonar.fincore.auth.Authorization;
import org.elyonar.fincore.notification.internal.channel.Channels;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Delivery policy, and the two reads an operator actually needs.
 *
 * <p>{@code GET /v1/suppressions} is the one that matters. It is not a debugging convenience — it
 * is what makes this service's defining invariant usable by a human: every consumed event ends as a
 * message or as a suppression carrying a reason code, so "why did my customer not get this?" is a
 * query. A support team without it reads logs and guesses, and a guess about whether a customer was
 * told their account moved is not an answer.
 *
 * <p>Neither read exposes a recipient address. The addresses are encrypted at rest and stay that
 * way: an operator needs to know *that* a message was owed and what happened to it, not the phone
 * number it was going to.
 */
@RestController
public class OperationsController {

    private static final List<String> CATEGORIES = List.of("TRANSACTIONAL", "SERVICE", "MARKETING");

    private final JdbcTemplate jdbc;
    private final Channels channels;

    public OperationsController(@Qualifier("appJdbcTemplate") JdbcTemplate jdbc, Channels channels) {
        this.jdbc = jdbc;
        this.channels = channels;
    }

    @GetMapping("/v1/policy")
    @Transactional(readOnly = true, transactionManager = "appTransactionManager")
    public List<Map<String, Object>> policy() {
        Authorization.require("notifications:read");
        scope();
        return jdbc.query(
                "SELECT category, channels, timezone, quiet_from, quiet_to FROM notification.channel_policy"
                        + " ORDER BY category",
                (rs, i) -> policyRow(rs));
    }

    /**
     * One policy row, with {@code channels} rendered as the list of strings it is.
     *
     * <p>{@code queryForList} hands a {@code text[]} back as the driver's own {@code java.sql.Array}
     * — a live object holding its {@code ResultSet}, which holds its {@code Statement}, which holds
     * the {@code Connection}. Serializing that walked the whole graph and put the deployment inside
     * the response body: the JDBC URL, the backend PID, the PostgreSQL version. Hard rule 8 says
     * deployment specifics are never committed; emitting them to every client that can read a
     * policy is worse than committing them.
     *
     * <p>It also broke the contract it leaked from. The document was large enough and malformed
     * enough that a client parsing it got nothing, so the portal showed an institution with a
     * configured policy the words "nothing sends" — the failure looked like missing configuration
     * rather than a broken read, which is the most expensive kind of wrong a settings screen can be.
     */
    private static Map<String, Object> policyRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("category", rs.getString("category"));
        row.put("channels", textArray(rs, "channels"));
        row.put("timezone", rs.getString("timezone"));
        row.put("quiet_from", rs.getString("quiet_from"));
        row.put("quiet_to", rs.getString("quiet_to"));
        return row;
    }

    /** A {@code text[]} as a plain list, copied out before the driver's array can carry anything else. */
    private static List<String> textArray(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        try {
            return List.of((String[]) array.getArray());
        } finally {
            array.free();
        }
    }

    @PutMapping("/v1/policy/{category}")
    @Transactional(transactionManager = "appTransactionManager")
    public Map<String, Object> setPolicy(@PathVariable String category, @RequestBody SetPolicy request) {
        var identity = Authorization.require("policy:write");
        scope();

        if (!CATEGORIES.contains(category)) {
            throw new ApiErrors.Unprocessable("UNKNOWN_CATEGORY", "not a category", Map.of("category", category));
        }
        if (request.channels() == null || request.channels().isEmpty()) {
            throw new ApiErrors.Unprocessable(
                    "POLICY_INCOMPLETE", "a category with no channels can send nothing", Map.of());
        }
        // Both ends or neither. A window with one end is a window nobody can evaluate, and the
        // schema refuses it too — this is the same rule stated where a caller can read it.
        if ((request.quietFrom() == null) != (request.quietTo() == null)) {
            throw new ApiErrors.Unprocessable(
                    "POLICY_INCOMPLETE", "a quiet window needs both ends or neither", Map.of());
        }
        for (String channel : request.channels()) {
            Channels.Channel found = channels.find(channel)
                    .orElseThrow(() -> new ApiErrors.Unprocessable(
                            "UNKNOWN_CHANNEL", "no such channel", Map.of("channel", channel)));
            if (!found.enabled()) {
                throw new ApiErrors.Unprocessable(
                        "CHANNEL_DISABLED", "channel is not enabled here", Map.of("channel", channel));
            }
        }

        jdbc.update(
                """
                INSERT INTO notification.channel_policy
                       (tenant_id, category, channels, timezone, quiet_from, quiet_to)
                VALUES (?,?,?::text[],coalesce(?, 'Africa/Lagos'),?::time,?::time)
                ON CONFLICT (tenant_id, category)
                DO UPDATE SET channels = EXCLUDED.channels,
                              timezone = EXCLUDED.timezone,
                              quiet_from = EXCLUDED.quiet_from,
                              quiet_to = EXCLUDED.quiet_to
                """,
                identity.tenantId(), category, "{" + String.join(",", request.channels()) + "}",
                request.timezone(), request.quietFrom(), request.quietTo());

        // Projected the same way as the read above, and for the same reason: this is the body a
        // client sees immediately after saving, so leaking the connection here would leak it to
        // exactly the caller who just proved they can write.
        return jdbc.query(
                        "SELECT category, channels, timezone, quiet_from, quiet_to FROM notification.channel_policy"
                                + " WHERE category = ?",
                        (rs, i) -> policyRow(rs),
                        category)
                .getFirst();
    }

    @GetMapping("/v1/deliveries")
    @Transactional(readOnly = true, transactionManager = "appTransactionManager")
    public List<Map<String, Object>> deliveries(@RequestParam(required = false) String moment) {
        Authorization.require("notifications:read");
        scope();
        // No recipient_address in the projection, deliberately. It is PII at rest and an operator
        // asking what happened to a message does not need the number it was going to.
        // Projected column by column for the same reason the policy is. `units` is the message's
        // billing size — SMS segments, an INT — and not an org unit despite the name; reading it
        // as an array would have thrown the first time a message actually existed to list.
        return jdbc.query(
                """
                SELECT id, business_moment_key, category, channel, template_key, template_version,
                       recipient_ref, units, state, attempts, next_attempt_at, created_at
                  FROM notification.notifications
                 WHERE (?::text IS NULL OR business_moment_key = ?)
                 ORDER BY created_at DESC
                 LIMIT 200
                """,
                (rs, i) -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("business_moment_key", rs.getString("business_moment_key"));
                    row.put("category", rs.getString("category"));
                    row.put("channel", rs.getString("channel"));
                    row.put("template_key", rs.getString("template_key"));
                    row.put("template_version", rs.getObject("template_version"));
                    row.put("recipient_ref", rs.getString("recipient_ref"));
                    row.put("units", rs.getObject("units"));
                    row.put("state", rs.getString("state"));
                    row.put("attempts", rs.getInt("attempts"));
                    row.put("next_attempt_at", rs.getString("next_attempt_at"));
                    row.put("created_at", rs.getString("created_at"));
                    return row;
                },
                moment, moment);
    }

    @GetMapping("/v1/suppressions")
    @Transactional(readOnly = true, transactionManager = "appTransactionManager")
    public List<Map<String, Object>> suppressions(
            @RequestParam(required = false) String moment, @RequestParam(required = false) String reason) {
        Authorization.require("notifications:read");
        scope();
        return jdbc.queryForList(
                """
                SELECT id, publisher, event_id, business_moment_key, category, channel, recipient_ref,
                       reason_code, detail, recorded_at
                  FROM notification.suppressions
                 WHERE (?::text IS NULL OR business_moment_key = ?)
                   AND (?::text IS NULL OR reason_code = ?)
                 ORDER BY recorded_at DESC
                 LIMIT 200
                """,
                moment, moment, reason, reason);
    }

    private void scope() {
        jdbc.queryForObject(
                "SELECT set_config('app.tenant_id', ?, true)",
                String.class,
                Authorization.tenantId().toString());
    }

    public record SetPolicy(List<String> channels, String timezone, String quietFrom, String quietTo) {}
}
