package org.elyonar.fincore.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The platform's first event consumer (ADR 0011).
 *
 * <p>It consumes domain events and turns them into messages a tenant's customer receives. It writes
 * no money, calls no money-path API, publishes no events, and holds no gateway credentials.
 *
 * <p>Its hardest problem is not sending. It is knowing exactly once that a message is owed — two
 * publishers describe one business moment, at-least-once delivery repeats them, and a replayed
 * topic looks identical to a busy morning — and being able to say afterwards why one was not sent.
 */
@SpringBootApplication
@EnableScheduling
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
