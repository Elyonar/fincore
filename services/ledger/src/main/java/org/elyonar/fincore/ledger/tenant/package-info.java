/**
 * Tenant identity and the database session scoping that enforces it.
 *
 * <p>Multi-tenancy here is defence in depth: application checks, composite {@code (tenant_id, id)}
 * foreign keys that make a cross-tenant reference structurally impossible, and row-level security
 * as the backstop. This package owns the third layer's precondition — putting the validated tenant
 * into the database session for exactly the life of one transaction.
 */
package org.elyonar.fincore.ledger.tenant;
