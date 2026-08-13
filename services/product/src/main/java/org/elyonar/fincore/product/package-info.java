/**
 * Product — the configuration engine.
 *
 * <h2>How this deployable is packaged</h2>
 *
 * {@code api} carries the shapes this service answers with; {@code internal} is everything else.
 * The split is inherited from when this was a module inside Core, where it was what stopped
 * another module importing these internals and was enforced by {@code ModuleBoundaryTest} (ADR
 * 0006). Since [ADR 0020] this is its own process with its own database, so a process boundary
 * enforces that already and the split is now organisational rather than load-bearing.
 *
 * <p>What is enforced, by {@code BoundaryTest} in this service's own suite, is the thing ADR 0020
 * attached as an obligation: no client onto the money path, no sibling deployable on the
 * classpath, no money type, and the internals reachable only from within this service. Core calls
 * Product; Product does not call Core.
 *
 * <h2>Rules that hold here</h2>
 *
 * <ul>
 *   <li><strong>This module returns decisions, never postings.</strong> It answers "is this
 *       permitted, what fee applies, under which configuration version" and Orchestration turns the
 *       answer into entries. A module that could post would be a second writer to the money path.
 *   <li><strong>Versions are append-only and a published one is immutable.</strong> A completed
 *       transaction records the version that priced it, so it stays explicable after the
 *       configuration moves on — signed-off configuration must stay reconstructible.
 *   <li><strong>Percentages are integer basis points.</strong> A percentage applied to money is a
 *       money calculation, so hard rule 1 applies: 250 means 2.50%, and no float touches it.
 *   <li>Publishing carries two names, and they must differ. Maker-checker on a product version is
 *       enforced by the row rather than by Orchestration's approvals table, because this module may
 *       not depend on that one.
 * </ul>
 */
package org.elyonar.fincore.product;
