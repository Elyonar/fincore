/**
 * Identity — the platform's token issuer and staff directory (ADR 0018).
 *
 * <p>Packaged as vertical slices named after the domain (service-scaffold §2): {@code token}
 * (keys, minting, JWKS), {@code auth} (login, sessions, throttling, the public API), {@code
 * bootstrap} (manifest seeding, ADR 0016), {@code api} (the error contract), and {@code internal}
 * (datasources, migrations, tenancy plumbing shared by the slices).
 *
 * <p>One boundary matters more than the layout: this deployable calls no other deployable. It is
 * the thing everything else points at, and the POM is where a reviewer checks that the arrow
 * never reverses.
 */
package org.elyonar.fincore.identity;
