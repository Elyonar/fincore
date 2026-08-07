package org.elyonar.fincore.notification.internal.channel;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Wires the senders and refuses to start if the registry and the code disagree. */
@Configuration
public class Senders {

    private static final Logger log = LoggerFactory.getLogger(Senders.class);

    /**
     * Resolves a channel to the one sender that serves it.
     *
     * <p>The startup check in this class is the reason it can be a lookup rather than a search with
     * a fallback: by the time anything calls it, every enabled channel has exactly one sender.
     */
    @Component
    public static class Registry {

        private final Map<String, MessageSender> byChannel;

        public Registry(List<MessageSender> senders) {
            this.byChannel = senders.stream().collect(Collectors.toMap(MessageSender::channel, s -> s, (a, b) -> a));
        }

        public MessageSender forChannel(String channel) {
            MessageSender sender = byChannel.get(channel);
            if (sender == null) {
                // Unreachable if the startup check ran. Kept because "unreachable" is a claim about
                // today's wiring, and a NullPointerException three frames later is a worse way to
                // discover it changed.
                throw new IllegalStateException("no sender for enabled channel " + channel);
            }
            return sender;
        }
    }

    /**
     * Refuses to run with a channel nothing can send on.
     *
     * <p>Without this, a channel enabled in the registry with nothing behind it queues messages that
     * can never leave, while the service reports itself healthy and a tenant's alerts pile up
     * invisibly. That is the same failure the logging adapter's banner exists to prevent, arriving
     * through configuration instead of through code — so it gets the same treatment: loud, and at
     * startup rather than at 2am.
     *
     * <p>On {@code ApplicationStartedEvent} rather than as an {@code InitializingBean}, because it
     * reads a migrated table and therefore has to run after Flyway. Ordering it by name against
     * Flyway's own initializer is brittle — the bean is named differently across Boot versions, and
     * a wrong name fails at startup with a message about a missing bean rather than anything to do
     * with channels. The lifecycle gives the same guarantee without naming anything.
     */
    @Component
    public static class WiringCheck {

        private final Channels channels;
        private final List<MessageSender> senders;

        public WiringCheck(Channels channels, List<MessageSender> senders) {
            this.channels = channels;
            this.senders = senders;
        }

        @EventListener(ApplicationStartedEvent.class)
        public void check() {
            Map<String, Long> perChannel = senders.stream()
                    .collect(Collectors.groupingBy(MessageSender::channel, Collectors.counting()));

            List<String> unserved = channels.enabled().stream()
                    .map(Channels.Channel::id)
                    .filter(id -> !perChannel.containsKey(id))
                    .toList();
            List<String> doubled = perChannel.entrySet().stream()
                    .filter(e -> e.getValue() > 1)
                    .map(Map.Entry::getKey)
                    .toList();

            if (!unserved.isEmpty() || !doubled.isEmpty()) {
                throw new IllegalStateException(
                        "channel wiring is wrong — enabled with no sender: " + unserved
                                + "; with several: " + doubled);
            }

            List<String> undelivered = senders.stream()
                    .filter(s -> !s.delivers())
                    .map(MessageSender::channel)
                    .sorted()
                    .toList();
            if (undelivered.isEmpty()) {
                log.info("notification: senders active for {}", perChannel.keySet());
            } else {
                log.warn(
                        "notification: channels {} DELIVER NOTHING — messages are queued, rendered and"
                                + " marked sent but reach no gateway. Development only; the messaging"
                                + " connector is what makes them real.",
                        undelivered);
            }
        }
    }

    /**
     * The development sender for every channel. <strong>Delivers nothing.</strong>
     *
     * <p>It reports {@code SENT} so the queue drains rather than growing without bound on a
     * developer's machine — which is precisely why it must be loud about delivering nowhere. A
     * component that silently does its job badly is worse than one that fails, because the system
     * reports itself working.
     *
     * <p>It logs the notification id and never the address. An address is PII at rest and in
     * flight, and a log line is neither encrypted nor purged on a retention schedule.
     */
    public static class LoggingMessageSender implements MessageSender {

        private static final Logger delivery = LoggerFactory.getLogger(LoggingMessageSender.class);

        private final String channel;

        public LoggingMessageSender(String channel) {
            this.channel = channel;
        }

        @Override
        public String channel() {
            return channel;
        }

        @Override
        public boolean delivers() {
            return false;
        }

        @Override
        public Result send(UUID notificationId, String address, Map<String, String> parts, String clientReference) {
            delivery.info(
                    "message (not delivered anywhere): channel={} notification={} ref={}",
                    channel, notificationId, clientReference);
            return Result.sent("log:" + clientReference);
        }
    }

    @Bean
    public MessageSender smsLoggingSender() {
        return new LoggingMessageSender("SMS");
    }

    @Bean
    public MessageSender emailLoggingSender() {
        return new LoggingMessageSender("EMAIL");
    }
}
