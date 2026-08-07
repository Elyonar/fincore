package org.elyonar.fincore.notification.internal.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.elyonar.fincore.auth.Authorization;
import org.elyonar.fincore.notification.internal.channel.Channels;
import org.elyonar.fincore.notification.internal.channel.Segments;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The templates a tenant sends from.
 *
 * <p>Built rather than left to raw SQL, because Core learned that lesson expensively: a schema only
 * tests can populate is not a module, and every endpoint {@code api.md} promised and nobody built
 * read as a description of a running system.
 *
 * <p>Note what is absent. There is no endpoint that edits a published version and none that deletes
 * one — a sent message records the version that produced it, so editing that version silently
 * changes what a past message said it said. A change is a new version, always.
 */
@RestController
@RequestMapping("/v1/templates")
public class TemplateController {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final Channels channels;

    public TemplateController(@Qualifier("appJdbcTemplate") JdbcTemplate jdbc, Channels channels) {
        this.jdbc = jdbc;
        this.channels = channels;
    }

    @GetMapping
    @Transactional(readOnly = true, transactionManager = "appTransactionManager")
    public List<TemplateView> list() {
        // A read is still a grant. This endpoint shipped without one for exactly as long as it took
        // a test to ask, which is the argument for having the test rather than the review.
        Authorization.require("notifications:read");
        scope();
        return jdbc.query(
                """
                SELECT id, template_key, channel, locale, version, status, units, published_by
                  FROM notification.templates
                 ORDER BY template_key, channel, locale, version
                """,
                TemplateController::view);
    }

    /** Creates version 1 as a DRAFT. Nothing is sendable until it is published. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional(transactionManager = "appTransactionManager")
    public TemplateView create(@RequestBody CreateTemplate request) {
        var identity = Authorization.require("templates:create");
        scope();

        Channels.Channel channel = channels.find(request.channel())
                .orElseThrow(() -> new ApiErrors.Unprocessable(
                        "UNKNOWN_CHANNEL", "no such channel", Map.of("channel", request.channel())));
        if (!channel.enabled()) {
            throw new ApiErrors.Unprocessable(
                    "CHANNEL_DISABLED", "channel is not enabled here", Map.of("channel", channel.id()));
        }
        requireParts(channel, request.parts());

        UUID id = jdbc.queryForObject(
                """
                INSERT INTO notification.templates
                       (tenant_id, template_key, channel, locale, version, status, parts)
                VALUES (?,?,?,?,
                        coalesce((SELECT max(version) + 1 FROM notification.templates
                                   WHERE template_key = ? AND channel = ? AND locale = ?), 1),
                        'DRAFT', ?::jsonb)
                RETURNING id
                """,
                UUID.class,
                identity.tenantId(), request.templateKey(), request.channel(), request.locale(),
                request.templateKey(), request.channel(), request.locale(),
                JSON.writeValueAsString(request.parts()));

        return read(id);
    }

    /**
     * Publishes a version, attributed and measured.
     *
     * <p>Measured here rather than at send time so a template over its channel's cap is refused
     * while someone is still editing it — not discovered on the bill. Yoruba and Igbo diacritics
     * force UCS-2, which drops an SMS segment from 160 characters to 70, so a template that fits in
     * English can cost three segments in translation.
     */
    @PostMapping("/{id}/versions/{version}/publish")
    @Transactional(transactionManager = "appTransactionManager")
    public TemplateView publish(@PathVariable UUID id, @PathVariable int version) {
        var identity = Authorization.require("templates:publish");
        scope();

        Map<String, Object> template = find(id);
        if ("PUBLISHED".equals(template.get("status"))) {
            throw new ApiErrors.Conflict(
                    "TEMPLATE_ALREADY_PUBLISHED", "a published version is immutable", Map.of("id", id.toString()));
        }
        if ((Integer) template.get("version") != version) {
            throw new ApiErrors.NotFound(
                    "TEMPLATE_NOT_FOUND", "no such version", Map.of("version", version));
        }

        Channels.Channel channel = channels.find((String) template.get("channel")).orElseThrow();
        Map<String, String> parts = parts(template);
        requireParts(channel, parts);

        int units = Segments.unitsFor(channel.contentModel(), parts.getOrDefault("body", ""));
        if (units > channel.maxUnits()) {
            throw new ApiErrors.Unprocessable(
                    "TEMPLATE_TOO_LONG",
                    "rendered worst case exceeds the channel's cap",
                    Map.of("units", units, "max", channel.maxUnits()));
        }

        jdbc.update(
                """
                UPDATE notification.templates
                   SET status = 'PUBLISHED', units = ?, published_by = ?, effective_from = now()
                 WHERE id = ?
                """,
                units, Authorization.initiatedBy(), id);
        return read(id);
    }

    private void requireParts(Channels.Channel channel, Map<String, String> parts) {
        for (String required : channel.requiredParts()) {
            if (parts == null || !parts.containsKey(required) || parts.get(required).isBlank()) {
                throw new ApiErrors.Unprocessable(
                        "TEMPLATE_PART_MISSING",
                        "the channel requires this part",
                        Map.of("part", required, "channel", channel.id()));
            }
        }
    }

    private Map<String, Object> find(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, template_key, channel, locale, version, status, parts::text AS parts"
                        + " FROM notification.templates WHERE id = ?",
                id);
        if (rows.isEmpty()) {
            throw new ApiErrors.NotFound("TEMPLATE_NOT_FOUND", "no such template", Map.of("id", id.toString()));
        }
        return rows.get(0);
    }

    private TemplateView read(UUID id) {
        return jdbc.queryForObject(
                "SELECT id, template_key, channel, locale, version, status, units, published_by"
                        + " FROM notification.templates WHERE id = ?",
                TemplateController::view,
                id);
    }

    /**
     * The response shape, named rather than inherited from the columns.
     *
     * <p>Returning a row map makes column names a published contract: renaming one becomes a
     * breaking change for every caller, and the API ends up database-shaped — the same mistake
     * ADR 0008 forbids in event payloads, for the same reason.
     */
    private static TemplateView view(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new TemplateView(
                rs.getObject("id", UUID.class),
                rs.getString("template_key"),
                rs.getString("channel"),
                rs.getString("locale"),
                rs.getInt("version"),
                rs.getString("status"),
                (Integer) rs.getObject("units"),
                rs.getString("published_by"));
    }

    public record TemplateView(
            UUID id,
            String templateKey,
            String channel,
            String locale,
            int version,
            String status,
            Integer units,
            String publishedBy) {}

    @SuppressWarnings("unchecked")
    private static Map<String, String> parts(Map<String, Object> template) {
        return JSON.readValue((String) template.get("parts"), Map.class);
    }

    private void scope() {
        jdbc.queryForObject(
                "SELECT set_config('app.tenant_id', ?, true)",
                String.class,
                Authorization.tenantId().toString());
    }

    public record CreateTemplate(String templateKey, String channel, String locale, Map<String, String> parts) {}
}
