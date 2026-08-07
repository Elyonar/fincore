package org.elyonar.fincore.notification.internal.template;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.elyonar.fincore.notification.internal.channel.Channels;
import org.elyonar.fincore.notification.internal.channel.Segments;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Templates, and the rendering of them.
 *
 * <p>Two rules carry the weight here. **A published version is never edited**, and a sent message
 * records the version that produced it — otherwise "what did we actually send that customer in
 * March" has no answer once the template moves on. And **rendering is total**: a template wanting a
 * variable the event did not carry produces no message at all, rather than one containing
 * {@code null} or a raw placeholder. A customer receiving "your account was debited with null" is
 * a worse outcome than a customer receiving nothing and an operator seeing a suppression.
 */
@Component
public class Templates {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** {@code {{name}}} — deliberately not a scripting syntax. A template is tenant content. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*}}");

    private final JdbcTemplate jdbc;
    private final Channels channels;

    public Templates(@Qualifier("appJdbcTemplate") JdbcTemplate jdbc, Channels channels) {
        this.jdbc = jdbc;
        this.channels = channels;
    }

    /** The live published version for this key, channel and locale, if there is one. */
    @Transactional(readOnly = true, transactionManager = "appTransactionManager")
    public Optional<Template> live(UUID tenantId, String key, String channel, String locale) {
        scopeTo(tenantId);
        return Optional.ofNullable(jdbc.query(
                """
                SELECT template_key, channel, locale, version, parts::text AS parts
                  FROM notification.templates
                 WHERE template_key = ? AND channel = ? AND locale = ?
                   AND status = 'PUBLISHED' AND effective_from <= now()
                 ORDER BY effective_from DESC, version DESC
                 LIMIT 1
                """,
                rs -> rs.next()
                        ? new Template(
                                rs.getString("template_key"),
                                rs.getString("channel"),
                                rs.getString("locale"),
                                rs.getInt("version"),
                                readParts(rs.getString("parts")))
                        : null,
                key, channel, locale));
    }

    /**
     * Renders every part, or refuses.
     *
     * <p>Returns the missing variable names rather than throwing on the first one: an operator
     * fixing a template wants the whole list, and finding them one redeployment at a time is how a
     * small problem becomes an afternoon.
     */
    public Rendered render(Template template, Map<String, String> variables) {
        Map<String, String> rendered = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();

        template.parts().forEach((part, text) -> {
            Matcher matcher = PLACEHOLDER.matcher(text);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                String name = matcher.group(1);
                String value = variables.get(name);
                if (value == null) {
                    missing.add(name);
                    value = "";
                }
                matcher.appendReplacement(out, Matcher.quoteReplacement(value));
            }
            matcher.appendTail(out);
            rendered.put(part, out.toString());
        });

        if (!missing.isEmpty()) {
            return Rendered.incomplete(missing);
        }

        Channels.Channel channel = channels.find(template.channel()).orElseThrow();
        // The body is what a length model measures. A subject is metadata on every channel that has
        // one, and no gateway bills for it.
        int units = Segments.unitsFor(channel.contentModel(), rendered.getOrDefault("body", ""));
        return units > channel.maxUnits() ? Rendered.tooLong(units) : Rendered.of(rendered, units);
    }

    private void scopeTo(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> readParts(String json) {
        return JSON.readValue(json, Map.class);
    }

    public record Template(String key, String channel, String locale, int version, Map<String, String> parts) {}

    /**
     * @param missingVariables non-empty when the template asked for something the event did not
     *     carry; {@code parts} is then meaningless and must not be sent
     */
    public record Rendered(Map<String, String> parts, int units, List<String> missingVariables, boolean overLimit) {

        static Rendered of(Map<String, String> parts, int units) {
            return new Rendered(parts, units, List.of(), false);
        }

        static Rendered incomplete(List<String> missing) {
            return new Rendered(Map.of(), 0, List.copyOf(missing), false);
        }

        static Rendered tooLong(int units) {
            return new Rendered(Map.of(), units, List.of(), true);
        }

        public boolean sendable() {
            return missingVariables.isEmpty() && !overLimit;
        }
    }
}
