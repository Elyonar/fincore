package org.elyonar.fincore.customer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Customers and the accounts they hold, as a deployable of its own.
 *
 * <p>It owns the only PII on the platform — names, phone numbers, email addresses, KYC tier and the
 * history of how it changed — and the record of which ledger account a person holds under which
 * product. It moves no money and posts nothing.
 *
 * <p>ADR 0006 named a process-level PII boundary as the first extraction trigger and expected this
 * module to be the first to fire it. It is, though for a reason 0006 stated less sharply than it
 * might have: the argument is not only that customer data deserves its own boundary, but that
 * every future consumer of it — lending, a partner API, any customer-owned surface — would
 * otherwise have to reach customer data by calling the service that moves money.
 *
 * <p>Core asks it two questions on every transaction, and both are deliberately <em>not</em>
 * cacheable. {@code check} returns status and KYC tier, and a customer frozen ten seconds ago must
 * be refused now. {@code productOfHeldAccount} is a security control rather than a convenience: the
 * money path resolves the governing product from the account precisely so a caller cannot name the
 * rules that judge its own transaction. Caching either would trade a correctness property for
 * latency, which is the wrong trade on this particular path.
 */
@SpringBootApplication
public class CustomerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomerApplication.class, args);
    }
}
