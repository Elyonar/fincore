package org.elyonar.fincore.notification.internal.contact;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Who to contact about an account, and what they agreed to.
 *
 * <p>An interface because the answer belongs to Core and the question is asked on every send. Event
 * payloads carry no PII by design (ADR 0008), so a consumer cannot read an address off the bus and
 * must ask — and having asked, it must not keep the answer: Core's customer schema is documented as
 * the only home for a customer profile, and a second copy here would make that claim false
 * platform-wide (design D-7).
 *
 * <p>Addresses are keyed by <em>address kind</em>, matching the channel registry. SMS and WhatsApp
 * are both {@code PHONE}, so a new channel on an existing kind needs nothing from Core at all.
 */
public interface ContactDirectory {

    /** @return empty when no live customer holds that account for this tenant */
    Optional<Contact> forAccount(UUID tenantId, UUID ledgerAccountId);

    /**
     * @param addresses by address kind. An address the customer does not have is absent rather than
     *     null-valued — an entry a caller must null-check is an entry that eventually is not
     *     checked.
     * @param locale BCP 47, or null when the customer was never asked. Null is a real answer and
     *     not a missing one: what to do about it is delivery policy, and the tenant's default is
     *     where that lives.
     * @param consent the explicit answers on record, and nothing more. Absence means the customer
     *     was never asked, and what that permits is this service's decision, not Core's.
     */
    record Contact(
            UUID customerId,
            String status,
            String locale,
            /** The account this contact was found by — what a message names so a customer can act on it. */
            String accountNumber,
            Map<String, String> addresses,
            List<Consent> consent) {

        /**
         * Whether this customer may be sent a message of this category on this channel.
         *
         * <p>Absence is not denial. A transactional alert is a fraud control nobody opts out of, so
         * it does not consult this at all; for everything else, an unanswered question is treated
         * as consent for service messages and refusal for marketing — opt-out and opt-in
         * respectively, which is what NDPR expects of each.
         */
        public boolean permits(String category, String channel) {
            return consent.stream()
                    .filter(c -> c.category().equals(category) && c.channel().equals(channel))
                    .findFirst()
                    .map(Consent::granted)
                    .orElse(!"MARKETING".equals(category));
        }
    }

    record Consent(String category, String channel, boolean granted) {}
}
