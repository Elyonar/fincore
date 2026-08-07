package org.elyonar.fincore.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.elyonar.fincore.notification.internal.AddressCipher;
import org.elyonar.fincore.notification.internal.channel.Channels;
import org.elyonar.fincore.notification.internal.contact.ContactDirectory;
import org.elyonar.fincore.notification.internal.contact.TransactionAccounts;
import org.elyonar.fincore.notification.internal.intake.EventIntake;
import org.elyonar.fincore.notification.internal.policy.DeliveryPolicy;
import org.elyonar.fincore.notification.internal.template.Templates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One event in, exactly one terminal answer out.
 *
 * <p>The suite is arranged around the invariant rather than around the code: every path either owes
 * a message or records why it does not, and each test names the reason it is proving. A consumer
 * that drops an event silently is the failure this service exists to make impossible, and the only
 * way to know it does not is to walk every branch that could.
 *
 * <p>Core is stubbed, not mocked over HTTP. What is under test is the pipeline's decisions, and a
 * real socket would test Jackson and the JDK's HttpClient instead.
 */
@SpringBootTest
@DisplayName("intake — a message, or a reason there is none")
class EventIntakeTest {

    @Autowired @Qualifier("appJdbcTemplate") private JdbcTemplate app;
    @Autowired private Channels channels;
    @Autowired private DeliveryPolicy policies;
    @Autowired private Templates templates;
    @Autowired private AddressCipher cipher;

    private UUID tenant;
    private UUID fromAccount;
    private UUID toAccount;
    private UUID sender;
    private UUID receiver;
    private long nextEventId;

    private final Map<UUID, ContactDirectory.Contact> contacts = new LinkedHashMap<>();
    private TransactionAccounts.Accounts accounts;

    @BeforeEach
    void seed() {
        tenant = UUID.randomUUID();
        fromAccount = UUID.randomUUID();
        toAccount = UUID.randomUUID();
        sender = UUID.randomUUID();
        receiver = UUID.randomUUID();
        nextEventId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);
        accounts = new TransactionAccounts.Accounts(fromAccount, toAccount);

        contacts.clear();
        contacts.put(fromAccount, contact(sender, Map.of("PHONE", "+2348000000001"), List.of()));
        contacts.put(toAccount, contact(receiver, Map.of("PHONE", "+2348000000002"), List.of()));

        app.execute("SET app.tenant_id = '" + tenant + "'");
        policy(List.of("SMS"), null, null);
    }

    /**
     * Templates are seeded per test rather than in setup, because the app role has no DELETE on
     * them — deliberately, since a published version is evidence — so "no template" is expressed by
     * never publishing one rather than by removing it. The grant makes the tidier fixture
     * impossible, which is the grant working.
     */
    private void seedSmsTemplates() {
        publishTemplate("debit.alert", "SMS", "Debit of {{amountMinor}} {{currency}}");
        publishTemplate("credit.alert", "SMS", "Credit of {{amountMinor}} {{currency}}");
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    @DisplayName("one transfer owes a message to both sides")
    void both_parties_are_notified() {
        seedSmsTemplates();
        EventIntake.Disposition outcome = intake().handle("core", transferCompleted());

        assertThat(outcome).isEqualTo(EventIntake.Disposition.NOTIFIED);
        // One business moment, two recipients. The unique key includes the recipient precisely so
        // this is two rows rather than a conflict.
        assertThat(recipients()).containsExactlyInAnyOrder(sender, receiver);
        assertThat(suppressions()).isEmpty();
    }

    @Test
    @DisplayName("the address is encrypted at rest and the template version is recorded")
    void what_is_stored_is_auditable_and_not_readable() {
        seedSmsTemplates();
        intake().handle("core", transferCompleted());

        String stored = app.queryForObject(
                "SELECT recipient_address FROM notification.notifications WHERE recipient_ref = ?",
                String.class, sender);

        assertThat(stored).doesNotContain("+234");
        assertThat(cipher.decrypt(stored)).isEqualTo("+2348000000001");
        // Without the version, "what did we actually send that customer in March" has no answer
        // once the template moves on.
        assertThat(app.queryForObject(
                        "SELECT template_version FROM notification.notifications WHERE recipient_ref = ?",
                        Integer.class, sender))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ the guards

    @Test
    @DisplayName("a redelivered event is handled once")
    void redelivery_changes_nothing() {
        seedSmsTemplates();
        String event = transferCompleted();

        assertThat(intake().handle("core", event)).isEqualTo(EventIntake.Disposition.NOTIFIED);
        assertThat(intake().handle("core", event)).isEqualTo(EventIntake.Disposition.ALREADY_HANDLED);

        assertThat(recipients()).hasSize(2);
    }

    @Test
    @DisplayName("two different events about one moment produce one message per recipient")
    void one_moment_notifies_once_even_from_two_events() {
        seedSmsTemplates();
        // The case (publisher, eventId) cannot see, and the reason a second key exists: these are
        // genuinely different events, so dedupe on the event id correctly lets both through.
        intake().handle("core", envelope(nextEventId++, "transfer.completed", "1"));
        intake().handle("core", envelope(nextEventId++, "transfer.completed", "1"));

        assertThat(recipients()).containsExactlyInAnyOrder(sender, receiver);
    }

    @Test
    @DisplayName("an event older than the age guard is suppressed, whatever the offset says")
    void stale_events_are_suppressed() {
        seedSmsTemplates();
        String old = envelope(nextEventId++, "transfer.completed", "1", Instant.now().minus(Duration.ofHours(2)), 1);

        assertThat(intake().handle("core", old)).isEqualTo(EventIntake.Disposition.SUPPRESSED);

        // A replayed topic is the case this exists for: nobody wants an alert about a transfer they
        // made two hours ago, and a consumer offset is an operational detail somebody resets.
        assertThat(reasons()).containsExactly("STALE_EVENT");
        assertThat(recipients()).isEmpty();
    }

    @Test
    @DisplayName("an event from an untrusted generation is fenced")
    void a_newer_epoch_is_fenced() {
        seedSmsTemplates();
        String rewound = envelope(nextEventId++, "transfer.completed", "1", Instant.now(), 9);

        assertThat(intake().handle("core", rewound)).isEqualTo(EventIntake.Disposition.SUPPRESSED);

        assertThat(reasons()).containsExactly("EPOCH_FENCED");
    }

    @Test
    @DisplayName("an event nobody decided to notify about is recorded as ignored, not dropped")
    void unmapped_events_are_recorded() {
        seedSmsTemplates();
        assertThat(intake().handle("core", envelope(nextEventId++, "transfer.initiated", "1")))
                .isEqualTo(EventIntake.Disposition.IGNORED);

        // Recorded rather than dropped: "the consumer saw it and chose nothing" and "the consumer
        // never received it" are different problems, and only one of them is a bug.
        assertThat(app.queryForObject(
                        "SELECT disposition FROM notification.consumed_events WHERE event_type = 'transfer.initiated'"
                                + " AND tenant_id = ?",
                        String.class, tenant))
                .isEqualTo("IGNORED");
    }

    // ------------------------------------------------------------------ the suppressions

    @Test
    @DisplayName("a customer with no address for any configured channel is recorded, not skipped")
    void no_address_is_a_reason() {
        seedSmsTemplates();
        contacts.put(fromAccount, contact(sender, Map.of("EMAIL", "ada@example.test"), List.of()));

        intake().handle("core", transferCompleted());

        // The receiver still gets one; only the sender is suppressed. A partial outcome is the
        // common case and must not fail the whole event.
        assertThat(recipients()).containsExactly(receiver);
        assertThat(reasons()).containsExactly("NO_ADDRESS");
    }

    @Test
    @DisplayName("opted out and no-address are different reasons")
    void opting_out_is_distinguished_from_being_unreachable() {
        seedSmsTemplates();
        contacts.put(
                fromAccount,
                contact(
                        sender,
                        Map.of("PHONE", "+2348000000001"),
                        List.of(new ContactDirectory.Consent("TRANSACTIONAL", "SMS", false))));

        intake().handle("core", transferCompleted());

        // An address we do not have and an address we were told not to use are different problems
        // with different fixes. Collapsing them makes the suppression report useless.
        assertThat(reasons()).containsExactly("OPTED_OUT");
    }

    @Test
    @DisplayName("no published template is a reason, not a crash")
    void a_missing_template_is_a_reason() {
        // Only the credit side has a template. Expressed by not publishing one, because the app
        // role cannot delete a published template and should not be able to.
        publishTemplate("credit.alert", "SMS", "Credit of {{amountMinor}}");

        intake().handle("core", transferCompleted());

        assertThat(reasons()).containsExactly("NO_TEMPLATE");
        assertThat(recipients()).containsExactly(receiver);
    }

    @Test
    @DisplayName("a template wanting a variable the event did not carry sends nothing")
    void a_missing_variable_suppresses() {
        publishTemplate("debit.alert", "SMS", "Debit of {{amountMinor}} to {{beneficiaryName}}");
        publishTemplate("credit.alert", "SMS", "Credit of {{amountMinor}}");

        intake().handle("core", transferCompleted());

        // "Your account was debited with null" is worse than nothing at all, and an operator seeing
        // the reason can fix the template.
        assertThat(reasons()).containsExactly("MISSING_VARIABLE");
        assertThat(app.queryForObject(
                        "SELECT detail->>'variables' FROM notification.suppressions WHERE tenant_id = ?",
                        String.class, tenant))
                .isEqualTo("beneficiaryName");
    }

    @Test
    @DisplayName("a tenant with no policy for the category is recorded")
    void no_policy_is_a_reason() {
        seedSmsTemplates();
        app.update("DELETE FROM notification.channel_policy WHERE tenant_id = ?", tenant);

        intake().handle("core", transferCompleted());

        assertThat(reasons()).containsOnly("NO_POLICY");
        assertThat(recipients()).isEmpty();
    }

    @Test
    @DisplayName("an unknown account is recorded against the event")
    void an_unknown_account_is_a_reason() {
        seedSmsTemplates();
        contacts.remove(fromAccount);

        intake().handle("core", transferCompleted());

        assertThat(reasons()).containsExactly("UNKNOWN_ACCOUNT");
        assertThat(recipients()).containsExactly(receiver);
    }

    // ------------------------------------------------------------------ policy

    @Test
    @DisplayName("quiet hours never silence a transactional alert")
    void a_debit_alert_ignores_quiet_hours() {
        seedSmsTemplates();
        policy(List.of("SMS"), "21:00", "07:00");

        // 02:00 local, squarely inside the window. A debit alert is a fraud control; one held until
        // 07:00 tells the customer five hours after the only moment they could have acted.
        EventIntake atTwoAm = intake(Clock.fixed(Instant.parse("2026-08-06T01:00:00Z"), ZoneOffset.UTC));

        assertThat(atTwoAm.handle("core", transferCompleted())).isEqualTo(EventIntake.Disposition.NOTIFIED);
        assertThat(reasons()).isEmpty();
    }

    @Test
    @DisplayName("channel fallback picks the first channel the customer can actually receive")
    void fallback_walks_the_configured_order() {
        seedSmsTemplates();
        policy(List.of("EMAIL", "SMS"), null, null);
        publishTemplate("debit.alert", "EMAIL", "Debit", "Debit of {{amountMinor}}");
        publishTemplate("credit.alert", "EMAIL", "Credit", "Credit of {{amountMinor}}");
        // The sender has no email, so falls through to SMS; the receiver has both and takes email.
        contacts.put(toAccount, contact(receiver, Map.of("PHONE", "+234800", "EMAIL", "x@example.test"), List.of()));

        intake().handle("core", transferCompleted());

        assertThat(channelFor(sender)).isEqualTo("SMS");
        assertThat(channelFor(receiver)).isEqualTo("EMAIL");
    }

    // ------------------------------------------------------------------ locale

    @Test
    @DisplayName("a customer's own language is used when the tenant has published it")
    void the_customer_language_wins() {
        seedSmsTemplates();
        publishTemplate("debit.alert", "SMS", "yo", null, "Owo {{amountMinor}} ti jade");
        contacts.put(fromAccount, contact(sender, "yo", Map.of("PHONE", "+2348000000001"), List.of()));

        intake().handle("core", transferCompleted());

        assertThat(localeFor(sender)).isEqualTo("yo");
        // The other side never said, so it gets the tenant's default rather than the sender's
        // language — a locale is a property of a person, not of a transfer.
        assertThat(localeFor(receiver)).isEqualTo("en");
    }

    @Test
    @DisplayName("a customer whose language has no template falls back rather than going silent")
    void an_untranslated_locale_falls_back() {
        seedSmsTemplates();
        contacts.put(fromAccount, contact(sender, "ha", Map.of("PHONE", "+2348000000001"), List.of()));

        intake().handle("core", transferCompleted());

        // This is what makes translating one alert at a time safe: adding a Hausa-speaking customer
        // must not silence them until somebody finishes the translation.
        assertThat(localeFor(sender)).isEqualTo("en");
        assertThat(reasons()).isEmpty();
    }

    @Test
    @DisplayName("the tenant default is the tenant's, not the platform's")
    void the_tenant_chooses_its_own_default() {
        policy(List.of("SMS"), null, null, "yo");
        publishTemplate("debit.alert", "SMS", "yo", null, "Owo {{amountMinor}} ti jade");
        publishTemplate("credit.alert", "SMS", "yo", null, "Owo {{amountMinor}} ti wole");

        intake().handle("core", transferCompleted());

        // Nobody asked either customer, and the tenant writes in Yoruba. Hardcoding 'en' anywhere
        // would have made this tenant unable to speak to its own customers.
        assertThat(localeFor(sender)).isEqualTo("yo");
        assertThat(localeFor(receiver)).isEqualTo("yo");
    }

    @Test
    @DisplayName("no template in any of the preferred languages is a suppression that names them")
    void an_untranslated_everything_is_recorded() {
        policy(List.of("SMS"), null, null, "ig");
        contacts.put(fromAccount, contact(sender, "ha", Map.of("PHONE", "+2348000000001"), List.of()));

        intake().handle("core", transferCompleted());

        assertThat(reasons()).contains("NO_TEMPLATE");
        assertThat(app.queryForList(
                        "SELECT detail->>'locales' FROM notification.suppressions WHERE tenant_id = ?"
                                + " AND reason_code = 'NO_TEMPLATE'",
                        String.class, tenant))
                .contains("ha,ig");
    }

    private String localeFor(UUID recipient) {
        return app.queryForObject(
                "SELECT locale FROM notification.notifications WHERE recipient_ref = ?", String.class, recipient);
    }

    // ------------------------------------------------------------------ harness

    private EventIntake intake() {
        return intake(Clock.systemUTC());
    }

    private EventIntake intake(Clock clock) {
        return new EventIntake(
                app,
                (t, id) -> Optional.ofNullable(accounts),
                (t, account) -> Optional.ofNullable(contacts.get(account)),
                policies,
                templates,
                channels,
                cipher,
                Duration.ofMinutes(15),
                1,
                clock);
    }

    private static ContactDirectory.Contact contact(
            UUID id, Map<String, String> addresses, List<ContactDirectory.Consent> consent) {
        return contact(id, null, addresses, consent);
    }

    /** @param locale null is the normal case — a customer nobody asked, who gets the tenant default */
    private static ContactDirectory.Contact contact(
            UUID id, String locale, Map<String, String> addresses, List<ContactDirectory.Consent> consent) {
        return new ContactDirectory.Contact(id, "ACTIVE", locale, addresses, consent);
    }

    private String transferCompleted() {
        return envelope(nextEventId++, "transfer.completed", "1");
    }

    private String envelope(long eventId, String type, String saga) {
        return envelope(eventId, type, saga, Instant.now(), 1);
    }

    private String envelope(long eventId, String type, String saga, Instant occurredAt, long epoch) {
        return ("{\"eventId\":%d,\"eventType\":\"%s\",\"aggregateId\":\"%s\",\"tenantId\":\"%s\","
                        + "\"occurredAt\":\"%s\",\"epoch\":%d,\"payload\":"
                        + "{\"transactionId\":\"%s\",\"amountMinor\":\"100000\",\"feeMinor\":\"0\","
                        + "\"currency\":\"NGN\"}}")
                .formatted(
                        eventId, type, sagaId(saga), tenant, occurredAt.toString(), epoch, sagaId(saga));
    }

    /** A stable UUID per logical saga, so two events can name one moment. */
    private String sagaId(String seed) {
        return UUID.nameUUIDFromBytes((tenant + ":" + seed).getBytes()).toString();
    }

    private void policy(List<String> order, String from, String to) {
        policy(order, from, to, "en");
    }

    private void policy(List<String> order, String from, String to, String defaultLocale) {
        app.update("DELETE FROM notification.channel_policy WHERE tenant_id = ?", tenant);
        app.update(
                """
                INSERT INTO notification.channel_policy
                       (tenant_id, category, channels, timezone, default_locale, quiet_from, quiet_to)
                VALUES (?, 'TRANSACTIONAL', ?::text[], 'Africa/Lagos', ?, ?::time, ?::time)
                """,
                tenant, "{" + String.join(",", order) + "}", defaultLocale, from, to);
    }

    private void publishTemplate(String key, String channel, String body) {
        publishTemplate(key, channel, null, body);
    }

    private void publishTemplate(String key, String channel, String subject, String body) {
        publishTemplate(key, channel, "en", subject, body);
    }

    private void publishTemplate(String key, String channel, String locale, String subject, String body) {
        String parts = subject == null
                ? "{\"body\":\"" + body + "\"}"
                : "{\"subject\":\"" + subject + "\",\"body\":\"" + body + "\"}";
        app.update(
                """
                INSERT INTO notification.templates
                       (tenant_id, template_key, channel, locale, version, status, parts, units, published_by)
                VALUES (?,?,?,?,1,'PUBLISHED',?::jsonb,1,'test')
                """,
                tenant, key, channel, locale, parts);
    }

    private List<UUID> recipients() {
        return app.queryForList(
                "SELECT recipient_ref FROM notification.notifications WHERE tenant_id = ?", UUID.class, tenant);
    }

    private String channelFor(UUID recipient) {
        return app.queryForObject(
                "SELECT channel FROM notification.notifications WHERE recipient_ref = ?", String.class, recipient);
    }

    private List<String> reasons() {
        return app.queryForList(
                "SELECT reason_code FROM notification.suppressions WHERE tenant_id = ?", String.class, tenant);
    }

    private List<String> suppressions() {
        return reasons();
    }
}
