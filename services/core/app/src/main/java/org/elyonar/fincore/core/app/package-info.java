/**
 * Core's assembly — wiring, and no domain logic whatsoever.
 *
 * <p>This is the deployable that holds {@code customer}, {@code product} and {@code orchestration}
 * (ADR 0006). What lives here is what belongs to none of them: the Spring application, the six
 * datasources and their transaction managers, Flyway's three per-module migration runs, the outbox
 * relay, the saga worker, and the startup summary.
 *
 * <p><strong>A domain rule appearing in this package is a bug</strong>, not a shortcut. The three
 * modules are separated by the POM graph, by ArchUnit and by per-schema database roles, and a
 * decision made here would sit outside all three mechanisms — reachable by everything and owned by
 * nothing.
 *
 * <p><strong>Six datasources, and the multiplicity is the point.</strong> Each module connects as
 * its own role, granted only on its own schema, so a cross-module query fails at runtime in the
 * test suite rather than surviving until someone tries to extract a module. The relay and the
 * worker have their own identities again, because they cross tenants and must do so through a
 * narrow policy rather than by holding {@code BYPASSRLS} — which would exempt them from row-level
 * security on every table at once.
 *
 * @see <a href="../../../../../../../docs/architecture.md">docs/architecture.md</a>
 */
package org.elyonar.fincore.core.app;
