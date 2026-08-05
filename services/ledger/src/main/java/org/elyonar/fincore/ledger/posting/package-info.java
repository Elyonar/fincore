/**
 * Transactions, entries, and the posting algorithm.
 *
 * <p>This is where money moves, so the ordering of operations is the design rather than a matter
 * of taste: idempotency is arbitrated by a unique index before anything else happens, balance rows
 * are locked in one global sorted order so deadlock is impossible rather than unlikely, and the
 * whole thing runs in a single transaction so a rejection leaves nothing behind.
 *
 * @see <a href="../../../../../../docs/posting-algorithm.md">docs/posting-algorithm.md</a>
 */
package org.elyonar.fincore.ledger.posting;
