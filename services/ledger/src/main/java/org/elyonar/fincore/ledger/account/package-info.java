/**
 * Accounts and their balances.
 *
 * <p>An account is an identity plus a lockable balance row; the two are created together and never
 * exist apart. Account identity — tenant, currency, type — is immutable once created, enforced by
 * trigger rather than by convention.
 */
package org.elyonar.fincore.ledger.account;
