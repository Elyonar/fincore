package org.elyonar.fincore.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The platform's identity provider (ADR 0018).
 *
 * <p>It verifies staff and service credentials and mints the tokens every other service verifies
 * via {@code libs/auth}. Client-driven by product decision: every flow is a first-party API and
 * there is no hosted login page. It states what a caller holds; it never decides what a holding
 * permits — deny-by-default enforcement stays in the owning services.
 *
 * <p>Its hardest problem is not issuing tokens. It is refusing in exactly one voice: an attacker
 * probing this surface must learn nothing from the difference between a user that does not exist,
 * a wrong password, a disabled account and a locked one — and a defender reading the audit trail
 * must learn everything.
 */
@SpringBootApplication
public class IdentityApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityApplication.class, args);
    }
}
