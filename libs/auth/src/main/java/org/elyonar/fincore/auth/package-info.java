/**
 * The shared authorization library: mechanics written once, decisions left to the owning service.
 *
 * <p><strong>What lives here.</strong> Token validation, extraction of the identity context
 * (principal, tenant, permissions, calling service), thread-scoped propagation, and the
 * {@code require} helpers. Every service imports this rather than reimplementing it, because
 * getting token verification subtly wrong in four places is four times the exposure and four times
 * the audit.
 *
 * <p><strong>What deliberately does not.</strong> Domain authorization. Whether an approval tier
 * covers an amount, whether maker differs from checker, whether a saga's state permits reversal,
 * which permission a given endpoint demands — these live in the service that owns the rule. A
 * shared library holding them would have to know every domain, and would be the one place a change
 * to any service's rules has to land.
 *
 * <p><strong>Three identities, verified by three different parties</strong> (ADR 0009): the
 * principal by the identity provider, the calling service by mutual TLS, the tenant by the token's
 * claim. They are kept separate because an examiner asks who authorized an action and which system
 * performed it as two questions, and because a tenant a caller could assert is not a boundary.
 *
 * <p><strong>Deny by default.</strong> No context denies. No permission denies. An unrecognised
 * caller denies. An endpoint nobody remembered to protect is closed rather than open — the filter
 * rejects unauthenticated requests before they reach a handler.
 */
package org.elyonar.fincore.auth;
