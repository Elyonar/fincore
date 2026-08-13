package org.elyonar.fincore.notification.internal.intake;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.elyonar.fincore.notification.internal.AddressCipher;
import org.elyonar.fincore.notification.internal.Suppressed;
import org.elyonar.fincore.notification.internal.channel.Channels;
import org.elyonar.fincore.notification.internal.contact.ContactDirectory;
import org.elyonar.fincore.notification.internal.contact.TransactionAccounts;
import org.elyonar.fincore.notification.internal.template.RenderContext;
import org.elyonar.fincore.notification.internal.policy.DeliveryPolicy;
import org.elyonar.fincore.notification.internal.template.Templates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * One event in, and exactly one terminal answer out.
 *
 * <p>This class is the service's defining invariant made executable: **every consumed event ends as
 * a message or as a suppression carrying a reason code.** There is no third outcome and no silent
 * drop, because "why did my customer not get an SMS?" has to be answerable from the database rather
 * than from logs and inference.
 *
 * <p>Everything runs in one transaction as the app role, scoped to the event's tenant. The
 * disposition row is written last and its unique key on {@code (publisher, event_id)} is what
 * arbitrates a duplicate delivery: two consumers racing the same event both do the work, one
 * commits, and the loser's transaction — including anything it wrote — rolls back whole.
 */
@Component
public class EventIntake {

    private static final Logger log = LoggerFactory.getLogger(EventIntake.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * Which event produces which message, for whom.
     *
     * <p>A closed map, not a convention over the event name. An event nobody has decided to notify
     * about is {@code IGNORED} — recorded, so an operator can see the consumer saw it and chose
     * nothing, which is different from the consumer never having received it.
     */
    private static final Map<String, List<Side>> NOTIFIED_EVENTS = Map.of(
            "transfer.completed",
            List.of(new Side("debit.alert", Party.FROM), new Side("credit.alert", Party.TO)));

    private final JdbcTemplate jdbc;
    // Explicit rather than @Transactional, because this boundary is load-bearing: the tenant scope
    // is a SET LOCAL and evaporates without a transaction around it, taking row-level security's
    // WITH CHECK with it. An annotation only applies through a proxy, so a directly constructed
    // intake would run every statement unscoped — which is exactly how a test found this.
    private final TransactionTemplate transaction;
    private final org.elyonar.fincore.notification.internal.TenantRegistry tenants;
    private final org.elyonar.fincore.notification.internal.template.TenantStarters starters;
    private final TransactionAccounts transactions;
    private final ContactDirectory directory;
    private final DeliveryPolicy policies;
    private final Templates templates;
    private final Channels channels;
    private final AddressCipher cipher;
    private final Duration maxEventAge;
    private final long trustedEpoch;
    private final Clock clock;

    // Two constructors, so the container is told which one is its. The other exists for tests that
    // must place an event at a chosen instant, and a test that could not would have to sleep.
    @Autowired
    public EventIntake(
            @Qualifier("appJdbcTemplate") JdbcTemplate jdbc,
            @Qualifier("appTransactionManager") PlatformTransactionManager transactionManager,
            org.elyonar.fincore.notification.internal.TenantRegistry tenants,
            org.elyonar.fincore.notification.internal.template.TenantStarters starters,
            TransactionAccounts transactions,
            ContactDirectory directory,
            DeliveryPolicy policies,
            Templates templates,
            Channels channels,
            AddressCipher cipher,
            @Value("${fincore.notification.max-event-age-ms:900000}") long maxEventAgeMs,
            @Value("${fincore.notification.trusted-epoch:1}") long trustedEpoch) {
        this(
                jdbc, transactionManager, tenants, starters, transactions, directory, policies, templates,
                channels, cipher, Duration.ofMillis(maxEventAgeMs), trustedEpoch, Clock.systemUTC());
    }

    /** Visible for tests, which need to place an event at a chosen point in a quiet-hours window. */
    public EventIntake(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            org.elyonar.fincore.notification.internal.TenantRegistry tenants,
            org.elyonar.fincore.notification.internal.template.TenantStarters starters,
            TransactionAccounts transactions,
            ContactDirectory directory,
            DeliveryPolicy policies,
            Templates templates,
            Channels channels,
            AddressCipher cipher,
            Duration maxEventAge,
            long trustedEpoch,
            Clock clock) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.tenants = tenants;
        this.starters = starters;
        this.transactions = transactions;
        this.directory = directory;
        this.policies = policies;
        this.templates = templates;
        this.channels = channels;
        this.cipher = cipher;
        this.maxEventAge = maxEventAge;
        this.trustedEpoch = trustedEpoch;
        this.clock = clock;
    }

    /** What happened to an event. Returned for tests and metrics; the database is the record. */
    public enum Disposition {
        /** At least one message is now owed. */
        NOTIFIED,
        /** Nothing is owed, and a row says why. */
        SUPPRESSED,
        /** Not an event this service notifies about. */
        IGNORED,
        /** Seen before. The first handling stands. */
        ALREADY_HANDLED
    }

    public Disposition handle(String publisher, String envelopeJson) {
        return transaction.execute(status -> handleScoped(publisher, envelopeJson));
    }

    private Disposition handleScoped(String publisher, String envelopeJson) {
        EventEnvelope event = EventEnvelope.parse(envelopeJson);
        scopeTo(event.tenantId());

        if (alreadyHandled(publisher, event.eventId())) {
            return Disposition.ALREADY_HANDLED;
        }

        // Before anything is decided about the event, decide whether the tenant is real. An event
        // naming a tenant nobody provisioned here would otherwise accumulate suppressions — and
        // eventually messages — under an id that means nothing.
        if (!tenants.isActive(event.tenantId())) {
            return suppressEvent(publisher, event, Suppressed.UNKNOWN_TENANT, Map.of());
        }

        // A real tenant has the platform's starter wording, whether or not anybody has opened a
        // settings screen. Once per tenant per process; see StarterTemplates for why it is here
        // and not at startup.
        starters.ensureFor(event.tenantId());

        // Fenced before anything else is decided. An event from a generation this consumer has been
        // told to distrust describes a history the publisher now denies, and acting on it is worse
        // than ignoring it.
        if (event.epoch() > trustedEpoch) {
            return suppressEvent(publisher, event, Suppressed.EPOCH_FENCED, Map.of("epoch", event.epoch()));
        }

        // The second replay guard, independent of the consumer offset (design D-5). An offset is an
        // operational detail somebody eventually resets; this is not.
        Duration age = Duration.between(event.occurredAt(), clock.instant());
        if (age.compareTo(maxEventAge) > 0) {
            return suppressEvent(publisher, event, Suppressed.STALE_EVENT, Map.of("ageSeconds", age.toSeconds()));
        }

        List<Side> sides = NOTIFIED_EVENTS.get(event.eventType());
        if (sides == null) {
            return record(publisher, event, Disposition.IGNORED);
        }

        Optional<TransactionAccounts.Accounts> accounts =
                transactions.forTransaction(event.tenantId(), UUID.fromString(event.aggregateId()));
        if (accounts.isEmpty()) {
            return suppressEvent(publisher, event, Suppressed.UNKNOWN_ACCOUNT, Map.of());
        }

        boolean anyOwed = false;
        for (Side side : sides) {
            UUID account = side.party() == Party.FROM ? accounts.get().from() : accounts.get().to();
            if (account == null) {
                // A reversal names no accounts. Not an error, and not a suppression either — there
                // is no recipient to record one against.
                continue;
            }
            anyOwed |= owe(publisher, event, side, account, accounts.get().facts());
        }

        return record(publisher, event, anyOwed ? Disposition.NOTIFIED : Disposition.SUPPRESSED);
    }

    /** @return true when a message is now owed to this side */
    private boolean owe(
            String publisher,
            EventEnvelope event,
            Side side,
            UUID account,
            TransactionAccounts.Facts facts) {
        Optional<ContactDirectory.Contact> found = directory.forAccount(event.tenantId(), account);
        if (found.isEmpty()) {
            suppress(event, side, null, null, Suppressed.UNKNOWN_ACCOUNT, Map.of("account", account.toString()));
            return false;
        }
        ContactDirectory.Contact contact = found.get();

        Optional<DeliveryPolicy.Policy> configured = policies.forCategory(event.tenantId(), CATEGORY);
        if (configured.isEmpty()) {
            suppress(event, side, contact.customerId(), null, Suppressed.NO_POLICY, Map.of());
            return false;
        }
        DeliveryPolicy.Policy policy = configured.get();

        if (policy.isQuiet(clock.instant())) {
            suppress(event, side, contact.customerId(), null, Suppressed.QUIET_HOURS, Map.of());
            return false;
        }

        // Ordered fallback, and the two failures are distinguished deliberately: an address the
        // customer does not have and an address they told us not to use are different problems with
        // different fixes, and collapsing them would make the suppression report useless.
        boolean sawAddress = false;
        for (String channelId : policy.channels()) {
            Optional<Channels.Channel> channel = channels.find(channelId).filter(Channels.Channel::enabled);
            if (channel.isEmpty()) {
                continue;
            }
            String address = contact.addresses().get(channel.get().addressKind());
            if (address == null) {
                continue;
            }
            sawAddress = true;
            if (!contact.permits(CATEGORY, channelId)) {
                continue;
            }
            return renderAndQueue(event, side, contact, policy, channel.get(), address, facts);
        }

        suppress(
                event, side, contact.customerId(), null,
                sawAddress ? Suppressed.OPTED_OUT : Suppressed.NO_ADDRESS,
                Map.of("channels", String.join(",", policy.channels())));
        return false;
    }

    private boolean renderAndQueue(
            EventEnvelope event,
            Side side,
            ContactDirectory.Contact contact,
            DeliveryPolicy.Policy policy,
            Channels.Channel channel,
            String address,
            TransactionAccounts.Facts facts) {

        Optional<Templates.Template> found = pickTemplate(event, side, contact, policy, channel);
        if (found.isEmpty()) {
            suppress(
                    event, side, contact.customerId(), channel.id(), Suppressed.NO_TEMPLATE,
                    Map.of("locales", String.join(",", localePreference(contact, policy))));
            return false;
        }
        Templates.Template template = found.get();

        // The context, not the raw payload. The payload is four fields by design (ADR 0008); what
        // a customer needs to read is assembled from Core's read API on the call this service
        // already made, plus which side of the movement this recipient is on.
        Templates.Rendered rendered = templates.render(
                template,
                RenderContext.of(
                        tenants.displayName(event.tenantId()),
                        contact.accountNumber(),
                        side.party() == Party.FROM ? RenderContext.DEBIT : RenderContext.CREDIT,
                        facts == null ? null : facts.reference(),
                        facts == null ? 0L : facts.amountMinor(),
                        facts == null ? 0L : facts.feeMinor(),
                        facts == null ? null : facts.currency(),
                        facts == null ? null : facts.channel(),
                        facts == null ? null : facts.occurredAt()));
        if (!rendered.missingVariables().isEmpty()) {
            suppress(
                    event, side, contact.customerId(), channel.id(), Suppressed.MISSING_VARIABLE,
                    Map.of("variables", String.join(",", rendered.missingVariables())));
            return false;
        }
        if (rendered.overLimit()) {
            suppress(
                    event, side, contact.customerId(), channel.id(), Suppressed.TOO_MANY_UNITS,
                    Map.of("units", rendered.units(), "max", channel.maxUnits()));
            return false;
        }

        int inserted = jdbc.update(
                """
                INSERT INTO notification.notifications
                       (tenant_id, business_moment_key, category, channel, template_key, template_version,
                        locale, recipient_ref, recipient_address, rendered, units)
                VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?)
                ON CONFLICT (tenant_id, business_moment_key, category, channel, recipient_ref) DO NOTHING
                """,
                event.tenantId(),
                event.businessMomentKey(),
                CATEGORY,
                channel.id(),
                template.key(),
                template.version(),
                template.locale(),
                contact.customerId(),
                cipher.encrypt(address),
                JSON.writeValueAsString(rendered.parts()),
                rendered.units());

        if (inserted == 0) {
            // Already owed. Two different events describing one moment, or a redelivery that got
            // past the event-id key — which is exactly the case this second key exists for.
            log.debug("message already owed for {} on {}", event.businessMomentKey(), channel.id());
        }
        return true;
    }

    /**
     * The customer's language first, then the tenant's.
     *
     * <p>PRD §4.9 wants Hausa, Yoruba and Igbo templates as tenant content, and the fallback is what
     * makes that safe to adopt gradually: a tenant translating one alert at a time still reaches
     * every customer, because a locale with no published template falls back rather than
     * suppressing. Without it, adding a Yoruba-speaking customer would silence them until somebody
     * finished translating.
     */
    private Optional<Templates.Template> pickTemplate(
            EventEnvelope event,
            Side side,
            ContactDirectory.Contact contact,
            DeliveryPolicy.Policy policy,
            Channels.Channel channel) {
        for (String locale : localePreference(contact, policy)) {
            Optional<Templates.Template> found =
                    templates.live(event.tenantId(), side.templateKey(), channel.id(), locale);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** Distinct and in order: a customer whose locale *is* the tenant default is one lookup, not two. */
    private static List<String> localePreference(ContactDirectory.Contact contact, DeliveryPolicy.Policy policy) {
        return Stream.of(contact.locale(), policy.defaultLocale())
                .filter(locale -> locale != null && !locale.isBlank())
                .distinct()
                .toList();
    }

    private boolean alreadyHandled(String publisher, long eventId) {
        Integer seen = jdbc.query(
                "SELECT 1 FROM notification.consumed_events WHERE publisher = ? AND event_id = ?",
                rs -> rs.next() ? 1 : null,
                publisher, eventId);
        return seen != null;
    }

    private Disposition suppressEvent(
            String publisher, EventEnvelope event, Suppressed reason, Map<String, Object> detail) {
        jdbc.update(
                """
                INSERT INTO notification.suppressions
                       (tenant_id, publisher, event_id, business_moment_key, reason_code, detail)
                VALUES (?,?,?,?,?,?::jsonb)
                """,
                event.tenantId(), publisher, event.eventId(), event.businessMomentKey(),
                reason.name(), JSON.writeValueAsString(detail));
        return record(publisher, event, Disposition.SUPPRESSED);
    }

    private void suppress(
            EventEnvelope event,
            Side side,
            UUID recipient,
            String channel,
            Suppressed reason,
            Map<String, Object> detail) {
        jdbc.update(
                """
                INSERT INTO notification.suppressions
                       (tenant_id, business_moment_key, category, channel, recipient_ref, reason_code, detail)
                VALUES (?,?,?,?,?,?,?::jsonb)
                """,
                event.tenantId(), event.businessMomentKey(), CATEGORY, channel, recipient,
                reason.name(), JSON.writeValueAsString(detail));
    }

    private Disposition record(String publisher, EventEnvelope event, Disposition disposition) {
        // Last, and the arbiter of a race. Two consumers handling one event both reach here; the
        // unique key lets one commit and rolls the other back whole, including anything it wrote.
        jdbc.update(
                """
                INSERT INTO notification.consumed_events
                       (publisher, event_id, tenant_id, event_type, occurred_at, epoch, disposition)
                VALUES (?,?,?,?,?,?,?)
                """,
                publisher, event.eventId(), event.tenantId(), event.eventType(),
                java.sql.Timestamp.from(event.occurredAt()), event.epoch(),
                // ALREADY_HANDLED never reaches here: handleScoped returns it before recording,
                // because the first handling's row already stands.
                disposition.name());
        return disposition;
    }

    private void scopeTo(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    /** v1 notifies about money moving, and money moving is transactional. */
    private static final String CATEGORY = DeliveryPolicy.TRANSACTIONAL;

    private record Side(String templateKey, Party party) {}

    private enum Party {
        FROM,
        TO
    }
}
