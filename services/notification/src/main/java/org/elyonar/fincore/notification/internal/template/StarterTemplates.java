package org.elyonar.fincore.notification.internal.template;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import org.elyonar.fincore.notification.internal.channel.Channels;
import org.elyonar.fincore.notification.internal.channel.Segments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The wording an institution starts with, so a new deployment tells its customers something.
 *
 * <p>Before this, the template table began empty and the first transaction any institution ever
 * took was suppressed {@code NO_TEMPLATE}. Nothing was broken — the service was correctly refusing
 * to invent words to send a stranger's customer — but the practical effect was that a bank had to
 * discover, from a reason code, that a banking platform does not know what a debit alert says.
 *
 * <p>It does. **The platform owns the vocabulary; the tenant owns the sentence** — the split ADR
 * 0017 already made for permissions and roles. These starters are published English wording marked
 * {@code origin = PLATFORM}. An institution changes them by publishing its own version of the same
 * key, channel and locale, which supersedes the starter through the ordinary
 * highest-published-version rule; the starter is never edited in place, so a later release can
 * improve it without overwriting anybody's deliberate choice.
 *
 * <p>Seeded on first sight of a tenant rather than at startup, because tenants are provisioned by
 * a script that writes registry rows directly (ADR 0016's interim path) and can appear at any time
 * — a startup-only seeder would leave every tenant registered after boot with nothing to send until
 * somebody restarted the service. The work is one query per tenant per process: idempotent, and
 * remembered in memory so the send path pays it once.
 */
@Component
public class StarterTemplates implements TenantStarters {

    private static final Logger log = LoggerFactory.getLogger(StarterTemplates.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** English. Translations are tenant content — PRD §4.9 names Hausa, Yoruba and Igbo. */
    private static final String LOCALE = "en";

    /**
     * The starters, and only the moments this service actually consumes.
     *
     * <p>Deliberately not the full catalogue the scope names. Seeding {@code statement.ready} while
     * nothing publishes a statement event puts a row in front of an administrator that can never
     * fire, and a template nobody can trigger is worse than an absent one: it reads as a feature.
     * Each new moment brings its starter with it.
     *
     * <p>The wording follows the shape a Nigerian bank customer already recognises — who it is
     * from, which account, how much and which way, and a reference they can quote — because an
     * alert that cannot be reconciled against a passbook is an alert that gets ignored, and an
     * ignored alert is a fraud control that does not work.
     */
    private static final List<Starter> STARTERS = List.of(
            new Starter(
                    "debit.alert",
                    "SMS",
                    Map.of(
                            "body",
                            "{{institution}}: Acct {{accountNumber | mask}} debited {{currency}}"
                                    + " {{amountMinor | money}} on {{occurredAt | date}}."
                                    + " Ref {{reference}}.")),
            new Starter(
                    "credit.alert",
                    "SMS",
                    Map.of(
                            "body",
                            "{{institution}}: Acct {{accountNumber | mask}} credited {{currency}}"
                                    + " {{amountMinor | money}} on {{occurredAt | date}}."
                                    + " Ref {{reference}}.")),
            new Starter(
                    "debit.alert",
                    "EMAIL",
                    Map.of(
                            "subject",
                            "{{institution}}: {{currency}} {{amountMinor | money}} debited",
                            "body",
                            "Account {{accountNumber | mask}} was debited {{currency}}"
                                    + " {{amountMinor | money}} on {{occurredAt | date}} at"
                                    + " {{occurredAt | time}}.\n\nReference: {{reference}}\n"
                                    + "Channel: {{channel}}\n\n{{institution}}")),
            new Starter(
                    "credit.alert",
                    "EMAIL",
                    Map.of(
                            "subject",
                            "{{institution}}: {{currency}} {{amountMinor | money}} credited",
                            "body",
                            "Account {{accountNumber | mask}} was credited {{currency}}"
                                    + " {{amountMinor | money}} on {{occurredAt | date}} at"
                                    + " {{occurredAt | time}}.\n\nReference: {{reference}}\n"
                                    + "Channel: {{channel}}\n\n{{institution}}")));

    private final JdbcTemplate jdbc;
    private final Channels channels;
    private final Set<UUID> seeded = ConcurrentHashMap.newKeySet();

    public StarterTemplates(@Qualifier("appJdbcTemplate") JdbcTemplate jdbc, Channels channels) {
        this.jdbc = jdbc;
        this.channels = channels;
    }

    /** Idempotent, and a no-op after the first call for a tenant in this process. */
    @Override
    @Transactional(transactionManager = "appTransactionManager")
    public void ensureFor(UUID tenantId) {
        if (!seeded.add(tenantId)) {
            return;
        }
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());

        int planted = 0;
        for (Starter starter : STARTERS) {
            Channels.Channel channel = channels.find(starter.channel()).orElse(null);
            if (channel == null || !channel.enabled()) {
                // A starter for a channel this deployment does not run is not an error; it is a
                // channel somebody has not switched on.
                continue;
            }
            int units = Segments.unitsFor(channel.contentModel(), starter.parts().getOrDefault("body", ""));
            if (units > channel.maxUnits()) {
                // The platform shipped wording that does not fit its own channel. Loud, because it
                // is our bug and it is silent otherwise — the tenant simply never gets a starter.
                log.error(
                        "starter template {}/{} measures {} units, over this channel's cap of {} — not seeded",
                        starter.key(), starter.channel(), units, channel.maxUnits());
                continue;
            }
            planted += jdbc.update(
                    """
                    INSERT INTO notification.templates
                           (tenant_id, template_key, channel, locale, version, status, parts, units,
                            origin, published_by)
                    VALUES (?,?,?,?,1,'PUBLISHED',?::jsonb,?, 'PLATFORM', 'system:starter-templates')
                    ON CONFLICT (tenant_id, template_key, channel, locale, version) DO NOTHING
                    """,
                    tenantId,
                    starter.key(),
                    starter.channel(),
                    LOCALE,
                    JSON.writeValueAsString(starter.parts()),
                    units);
        }

        if (planted > 0) {
            log.info("seeded {} platform starter template(s) for tenant {}", planted, tenantId);
        }
    }

    /** @param parts keyed by the channel's required part names — `body`, and `subject` for email */
    private record Starter(String key, String channel, Map<String, String> parts) {

        Starter {
            parts = new LinkedHashMap<>(parts);
        }
    }
}
