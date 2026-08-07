package org.elyonar.fincore.notification.internal.channel;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The channel registry (design D-13).
 *
 * <p>A channel is a row, not an enum. That single choice is what makes a fifth channel an INSERT
 * and a sender class rather than a migration, a schema change, and an edit to every table that
 * names a channel. Push is already in the PRD; WhatsApp is a question somebody will ask.
 *
 * <p>Nothing else in this service is channel-aware. The intake, the deduplication, the policy
 * engine, the send queue and the suppression catalogue all read a descriptor and act on its four
 * properties.
 *
 * <p>Loaded once at startup and held: which channels the platform can speak is a deployment fact,
 * changed by a migration and a restart, not per request. A registry re-read on every send would be
 * a query on the hot path answering a question that never changes between deployments.
 */
@Component
// Reads a migrated table on first use, so it is ordered against the migration bean. An
// already-migrated database hides a missing ordering; an empty one does not, which is what a
// dedicated test database found on its first run.
@DependsOn("flyway")
public class Channels {

    private final JdbcTemplate jdbc;
    private volatile Map<String, Channel> byId;

    public Channels(@Qualifier("appJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Loaded on first use rather than in the constructor.
     *
     * <p>Not laziness for its own sake: reading a migrated table at construction makes this bean's
     * ordering against Flyway's initializer load-bearing, and getting that wrong produces a startup
     * error naming a missing relation rather than a missing migration. Deferring the read means
     * Flyway's autoconfiguration is enough — the same arrangement the ledger uses — and no bespoke
     * migration wiring has to exist to hold this one bean's hand.
     */
    private Map<String, Channel> registry() {
        Map<String, Channel> loaded = byId;
        if (loaded == null) {
            synchronized (this) {
                loaded = byId;
                if (loaded == null) {
                    byId = loaded = load();
                }
            }
        }
        return loaded;
    }

    private Map<String, Channel> load() {
        return jdbc
                .query(
                        """
                        SELECT id, address_kind, required_parts, content_model, max_units, enabled
                          FROM notification.channels ORDER BY id
                        """,
                        (rs, row) ->
                                new Channel(
                                        rs.getString("id"),
                                        rs.getString("address_kind"),
                                        List.of((String[]) rs.getArray("required_parts").getArray()),
                                        ContentModel.valueOf(rs.getString("content_model")),
                                        rs.getInt("max_units"),
                                        rs.getBoolean("enabled")))
                .stream()
                .collect(Collectors.toMap(Channel::id, c -> c));
    }

    public Optional<Channel> find(String id) {
        return Optional.ofNullable(registry().get(id));
    }

    /** The channels this deployment can actually speak. */
    public List<Channel> enabled() {
        return registry().values().stream().filter(Channel::enabled).sorted((a, b) -> a.id().compareTo(b.id())).toList();
    }

    /**
     * One channel.
     *
     * @param addressKind the kind of address it needs. Several channels share one — SMS and
     *     WhatsApp are both {@code PHONE} — which is why Customer returns addresses keyed by kind
     *     and a new channel on an existing kind costs nothing outside this service.
     * @param requiredParts the template parts a message must have. SMS is {@code body}; email is
     *     {@code subject, body}; push would be {@code title, body}.
     * @param maxUnits the cap under {@code contentModel}'s unit — segments for SMS, messages
     *     otherwise.
     */
    public record Channel(
            String id,
            String addressKind,
            List<String> requiredParts,
            ContentModel contentModel,
            int maxUnits,
            boolean enabled) {}

    /**
     * How a channel measures what it sends.
     *
     * <p>A new length model is the one genuinely new concept a future channel could bring, and this
     * enum is where it belongs — not an {@code if (channel.equals("SMS"))} somewhere in rendering.
     */
    public enum ContentModel {
        /** GSM-7 at 160 characters per segment, UCS-2 at 70. Diacritics force the second. */
        SEGMENTED,
        /** One unit per message, however long it is. */
        PLAIN
    }
}
