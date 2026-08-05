/**
 * The transactional outbox and the relay that drains it.
 *
 * <p>The ledger makes no synchronous outbound calls, so events leave through a table rather than a
 * client. Writing the event in the same transaction as the state change is what makes "an event
 * exists if and only if the money moved" true; the relay then carries committed rows outward,
 * where a broker outage delays delivery instead of failing a posting.
 */
package org.elyonar.fincore.ledger.outbox;
