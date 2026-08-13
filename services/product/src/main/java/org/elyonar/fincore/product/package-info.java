/**
 * Product — the configuration engine.
 *
 * <h2>How this module is packaged</h2>
 *
 * {@code api} is what Orchestration reads through; {@code internal} is everything else, closed to
 * every other module by {@code ModuleBoundaryTest} and by a database role granted on this schema
 * alone (ADR 0006).
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
