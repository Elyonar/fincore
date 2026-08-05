/**
 * Accounting periods and their close.
 *
 * <p>A closed period rejects postings whose value date falls inside it. That is what makes a
 * statement over that period immutable by construction, and it is why statements need no snapshot
 * machinery at all.
 */
package org.elyonar.fincore.ledger.period;
