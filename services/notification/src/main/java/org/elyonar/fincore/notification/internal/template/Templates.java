package org.elyonar.fincore.notification.internal.template;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
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

    /**
     * {@code {{name}}} or {@code {{name | filter}}} — deliberately not a scripting syntax.
     *
     * <p>A template is tenant content, and content a tenant writes must not be able to loop, call
     * anything, or read anything the context did not hand it. Substitution plus a closed set of
     * named filters is the whole language, and the filters exist so that formatting money is
     * decided once by the platform rather than a hundred times, differently, by administrators.
     */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*(?:\\|\\s*([a-zA-Z0-9_]+)\\s*)?}}");

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
     * The closed set of filters a template may use.
     *
     * <p>Closed on purpose. Every one of these is a decision about how this platform presents money
     * or identity to a customer, and a decision made once is a decision that can be corrected once.
     * The alternative — each tenant formatting minor units in their own template — produces a
     * portfolio where ₦2,500.00, 2500.00 and 250000 all appear, and the third one is a support call.
     *
     * <ul>
     *   <li>{@code money} — minor units to the major unit, grouped: {@code 250000} → {@code 2,500.00}
     *   <li>{@code date} — an ISO instant to something a person reads: {@code 12-Aug-2026}
     *   <li>{@code time} — the clock part, for an alert where the minute matters: {@code 09:23}
     *   <li>{@code mask} — all but the last four digits: {@code 0000000001} → {@code ******0001}
     * </ul>
     *
     * <p>An unknown filter is a **missing variable**, not a silent pass-through. A template
     * referring to a filter this platform does not have is a template written against a version
     * that does not exist, and rendering it unfiltered would put raw minor units in front of a
     * customer while looking like it worked.
     */
    private static String apply(String filter, String value, List<String> missing, String name) {
        if (filter == null) {
            return value;
        }
        try {
            return switch (filter) {
                case "money" -> money(value);
                case "date" -> DATE.format(OffsetDateTime.parse(value));
                case "time" -> TIME.format(OffsetDateTime.parse(value));
                case "mask" -> mask(value);
                default -> {
                    missing.add(name + "|" + filter);
                    yield "";
                }
            };
        } catch (RuntimeException e) {
            // A value the filter cannot read is reported the same way a missing one is: the message
            // is not sent, and the reason names the pair that failed.
            missing.add(name + "|" + filter);
            return "";
        }
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    /**
     * Minor units to major, with grouping and exactly two decimal places.
     *
     * <p>Integer arithmetic throughout, and `BoundaryTest` enforces it: this service may not so
     * much as import {@code BigDecimal}, "because units are integers; a decimal here is a rounding
     * argument with a gateway". The rule holds for *displaying* money as much as for holding it —
     * the moment a formatter is allowed a decimal type, the argument for one in a calculation is
     * already half made. Splitting on 100 and grouping the whole part is all this ever needed.
     */
    private static String money(String minorUnits) {
        long minor = Long.parseLong(minorUnits.trim());
        boolean negative = minor < 0;
        long absolute = Math.abs(minor);
        long major = absolute / 100;
        long fraction = absolute % 100;
        String grouped = new java.text.DecimalFormat("#,##0").format(major);
        return (negative ? "-" : "") + grouped + "." + (fraction < 10 ? "0" + fraction : Long.toString(fraction));
    }

    /** All but the last four, so a customer recognises the account and a thief learns nothing. */
    private static String mask(String value) {
        if (value.length() <= 4) {
            return value;
        }
        return "*".repeat(value.length() - 4) + value.substring(value.length() - 4);
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
                String filter = matcher.group(2);
                String value = variables.get(name);
                if (value == null) {
                    missing.add(name);
                    value = "";
                } else {
                    value = apply(filter, value, missing, name);
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
