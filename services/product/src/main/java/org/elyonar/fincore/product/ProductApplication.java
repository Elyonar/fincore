package org.elyonar.fincore.product;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The product catalogue and its pricing, as a deployable of its own.
 *
 * <p>It answers two quite different kinds of question. Authoring is slow, deliberate and
 * maker-checked: a product is created, a version is drafted, its fees and limits are written, and a
 * second person publishes it. Deciding is fast and read-only: given a product, a tier, a channel
 * and an amount, is this permitted and what does it cost. Core asks the second question on every
 * transaction and never asks the first.
 *
 * <p>It is safe to ask that question across a network because the answer cannot change underneath
 * the asker. A published version and every rule beneath it are immutable, enforced by the
 * {@code reject_published_edit} and {@code reject_published_rule_write} triggers rather than by
 * application code being careful — so a decision keyed by version is cacheable by construction.
 * That property is the reason this module was the first one worth extracting.
 *
 * <p>It writes no money and posts nothing. It reads the ledger exactly once, at authoring time, to
 * refuse a fee rule naming an account that does not exist — the check that used to be an inverted
 * port implemented by Orchestration, because a Core module was forbidden an HTTP client. A
 * deployable is allowed one, so the inversion is gone.
 */
@SpringBootApplication
public class ProductApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductApplication.class, args);
    }
}
