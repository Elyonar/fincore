/**
 * Types shared by every slice: the error catalog and the exception that carries it.
 *
 * <p>This package depends on no other slice, and every other slice may depend on it. Anything that
 * belongs to one capability belongs in that capability's package instead — {@code shared} is for
 * vocabulary, not for code that had nowhere else to go.
 */
package org.elyonar.fincore.ledger.shared;
