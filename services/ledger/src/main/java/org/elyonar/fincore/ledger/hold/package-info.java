/**
 * Reservations against an account's available balance.
 *
 * <p>A hold reduces what can be spent without moving any money, so nothing here writes an entry.
 * Capture is the exception, and it does not live here: consuming a hold is an argument to a
 * posting, so that the reservation and the money movement commit together. Releasing first and
 * posting second would open a window in which concurrent spending can strand an obligation the
 * bank has already settled externally.
 */
package org.elyonar.fincore.ledger.hold;
