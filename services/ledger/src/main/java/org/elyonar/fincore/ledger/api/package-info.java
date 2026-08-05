/**
 * The HTTP surface.
 *
 * <p>Thin by intention: controllers translate between JSON and commands and do no domain work at
 * all. Every rule that protects money lives in the domain slices and the schema, so that a second
 * entry point — a batch importer, an admin tool — cannot bypass it by not going through here.
 */
package org.elyonar.fincore.ledger.api;
