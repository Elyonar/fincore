# Convention — what every fincore service must have

The ledger was built first, and building it produced a set of practices that are
not optional and not obvious. Most of them are recorded inside
`services/ledger/docs/` because that is where they were discovered — which means
a second service would either rediscover them or, worse, ship without them and
report itself protected.

This document promotes them to platform convention. It is the checklist for
starting a service and the standard a service review is held to.

**It is not a template to copy blindly.** Every item below has a reason, and a
service that genuinely does not need one says so explicitly in its design rather
than omitting it quietly. An unstated omission reads as an oversight; a stated
one reads as a decision.

---

## 1. Documentation

A service documents itself. The root `docs/` holds only cross-cutting material.

```
services/<name>/README.md          # the map: purpose, boundaries, doc index, run instructions
services/<name>/docs/design.md     # the design index + decision log (DRAFT until AGREED)
services/<name>/docs/CHANGELOG.md  # every amendment once AGREED
services/<name>/docs/<topic>.md    # deep dives, one topic per file, indexed from the README
```

- **The README is the navigator, never the territory.** New topics get their own
  file and a row in the README's index table.
- **`design.md` carries a decision log** — decision, resolution, rationale — so
  a settled question is not re-litigated at every review. Superseded decisions
  are annotated, never deleted.
- **A design is DRAFT until marked AGREED. No domain code lands while DRAFT.**
  Once AGREED it is versioned as a whole and changes only by amendment; see
  [`design-changes.md`](design-changes.md).
- **The README carries an honest "Known limitations" section.** The list a reader
  will find should be the real one, not the hopeful one.

## 2. Module layout

- **Packages are vertical slices named after the domain** — `account`, `posting`,
  `hold` — not `controller`/`service`/`repository` layers. One capability lives
  in one directory.
- A `package-info.java` at the root explains how the service is packaged and
  why, so the convention survives the person who set it.
- **The `pom.xml` is an architectural control, not a dependency list.** A
  reviewer should be able to verify a boundary by reading it. Adding a dependency
  that crosses a stated boundary is a design change, not a build change.

## 3. Persistence

- **Plain SQL or an ORM is a per-service decision**, recorded in that service's
  `design.md` with its rationale. The ledger chose plain SQL over JDBC because
  its algorithm is written in terms of things an ORM takes away; a CRUD-shaped
  service may reasonably choose otherwise. What is *not* optional is that the
  choice is recorded.
- **Flyway, versioned, append-only.** An applied migration is never edited —
  corrections are new migrations.
- **Every constraint, trigger, index and policy is created in a migration**, and
  asserted by a schema-presence test (§7). Correctness that lives in database
  objects is correctness a careless migration can silently drop.
- **Zero-downtime changes follow expand → migrate → contract** (constitution #9:
  no batch windows, no scheduled downtime).
- `CREATE INDEX CONCURRENTLY` for indexes on hot tables, outside a transactional
  migration.

## 4. Database roles — two jobs, two identities

- **Migrations run as the schema owner. Traffic connects as a restricted role**
  (`NOSUPERUSER`, `NOBYPASSRLS`, DML only). DDL and traffic are different jobs
  and must not share an identity.
- **In a multi-module deployable, one role per module,** granted only on its own
  schema ([ADR 0006](../adr/0006-modular-core.md)). A cross-module query then
  fails in the test suite rather than surviving until an extraction.
- Local development creates the roles in `db/init/`; CI creates them in a
  workflow step; production provisions them with real secrets.

**This is not ceremony.** PostgreSQL exempts superusers and `BYPASSRLS` roles
from row-level security entirely — connect as the wrong role and every isolation
policy is inert while the catalog still reports it enabled.

## 5. Tenancy

Per [ADR 0007](../adr/0007-tenant-isolation-pattern.md), for any service holding
tenant-scoped data:

- `tenant_id` on every row, application-level scoping on every query
- Composite foreign keys on `(tenant_id, id)` so cross-tenant references cannot
  be expressed; any identifier not covered by one is named explicitly in the data
  model and carries its own test
- Row-level security **enabled and `FORCE`d** on every tenant-scoped table
- Tenant context via **`SET LOCAL` inside the request transaction** — never a
  session `SET`, because connections are pooled across tenants and a leaked
  session variable defeats the backstop in exactly the case it exists for
- A tenant registry, or validation against the service that owns tenant
  lifecycle. Row-level security isolates tenants; it cannot tell you a tenant is
  real.

## 6. Money, idempotency, and time

- **Integer minor units. No float, double, or decimal type touches a money
  value.** Enforced by ArchUnit.
- **All monetary fields in responses and event payloads are decimal strings**;
  requests accept numbers or strings. Balances and sums are unbounded and can
  exceed exact JSON number range.
- **Every creating operation takes a caller-supplied idempotency key**, unique
  per tenant, with a payload fingerprint. Same key + same payload replays; same
  key + different payload is a loud `409`, never a silent wrong answer.
- **A unique index arbitrates idempotency races, not application code.**
- **The retry rule is contract, both halves:** any 4xx is terminal for that key;
  any timeout, connection failure or 5xx means the outcome is *unknown* and the
  caller must retry the same key until it gets a definitive answer.
- **`java.time` only.** `java.util.Date`, `Calendar` and `java.sql.Date` are
  banned by ArchUnit — business dates are tenant-timezone dates and those types
  have no zone semantics worth trusting.
- **Dual attribution:** record the human or system principal and the calling
  service identity in **two separate columns**. Examiners ask who authorized and
  which system acted; one collapsed column answers neither.

## 7. Testing

- **Real PostgreSQL, never an in-memory substitute.** The locking, trigger,
  constraint and row-level-security behaviour under test *is* PostgreSQL's.
- **Schema-presence suite:** every trigger, partial unique index, composite
  foreign key, CHECK and policy exists **and fires**. A migration that loses a
  trigger fails CI, not an audit.
- **Schema-enforcement suite:** raw SQL attempts to violate each invariant the
  schema is supposed to prevent, and is rejected. Tamper-evidence must not depend
  on application code.
- **ArchUnit suite** for the boundaries this service claims, including a
  **canary rule that fails when the import set is empty** — every
  `no…should…` rule passes vacuously when nothing was imported, and a toolchain
  bump can silently make an entire suite enforce nothing.
- **Tests are named after the guarantee they prove**, not after the method they
  call.
- **Every suite in `testing.md` is marked IMPLEMENTED, PARTIAL or DEFERRED**, and
  only IMPLEMENTED suites gate merges. A design that describes verification which
  does not run is worse than an admitted gap: a reviewer or an agent reads it as
  fact. Moving a marker requires the tests to exist.

## 8. Events

Per [ADR 0008](../adr/0008-event-contract.md), for any service that publishes:

- **Transactional outbox** — the event is written in the same database
  transaction as the state change, so an event exists if and only if the change
  committed.
- **Relay polls `FOR UPDATE SKIP LOCKED` on unpublished rows, ordered by id.
  Never a watermark** — sequence values are assigned at insert, not commit, so a
  slow writer commits a low id after a higher one was relayed and a watermark
  skips it forever, silently.
- **Alert on oldest-unpublished age.** A dead relay must be caught in minutes,
  not at month-end reconciliation. This is a metric, not only a log line.
- Published rows are purged on a retention schedule. The outbox is a delivery
  queue and must never become a second, unaudited event archive.
- The platform envelope, thin payloads, and per-aggregate ordering are ADR 0008's.

Consumers deduplicate on `(publisher, eventId)` and are **state-based** — react
by fetching current state through the publisher's read API, never by replaying a
sequence.

## 9. API surface

- Versioned path prefix (`/v1`), stable within a major version.
- **OpenAPI generated from the code**, not hand-maintained — a generated document
  cannot describe an endpoint the service does not serve. The agreed `api.md`
  remains the *design*; the generated spec is its executable reflection.
- **Errors follow [`error-contract.md`](error-contract.md)** — `code`, a
  `reason` where one code spans several causes, machine-readable `details`, and
  a `message` that is developer English nobody displays or parses. A single
  generic error is a debugging cost paid forever by every caller; an error whose
  specifics live only in an English sentence is a service that cannot be
  deployed outside anglophone markets.
- **Copy `ErrorCodeCatalogTest`** so the build fails when a code or reason
  exists without documentation, or is documented without existing.
- Not-found and wrong-tenant are deliberately indistinguishable.

## 10. Operations

- Health and readiness endpoints; the actuator surface deliberately limited.
- **A startup summary that names the active adapters and warns loudly when a
  development-only one is in use.** A service that silently delivers nothing is
  the failure this prevents.
- Metrics for anything with a stated operational alarm — a threshold documented
  but unmeasured is not an alarm.
- A `Dockerfile` producing a trimmed runtime; if the module list is part of the
  build contract, say so where someone will read it before changing it.

## 11. CI

- The service builds and tests in `./mvnw verify` at the root.
- CI provisions **its database and every role it needs** before the build step.
- If the service has an AGREED design, the `design-changelog` job already covers
  it — that job globs `services/*/docs/`, so no per-service change is needed.

---

## Checklist for a new service

Design, before any code:

- [ ] `design.md` written, reviewed, and marked **AGREED** with a version
- [ ] Data model, API surface, algorithm and testing docs written and consistent
- [ ] Decision log records every choice that a reviewer would otherwise re-open
- [ ] Boundaries stated as "never in this service", not only as what it does
- [ ] Anything forcing another service to change has an ADR

Scaffold, before the first feature:

- [ ] Flyway configured; first migration creates the schema *and* its constraints
- [ ] Restricted application role provisioned in `db/init/`, CI, and deployment
- [ ] Row-level security enabled and `FORCE`d; `SET LOCAL` tenant context
- [ ] Schema-presence and schema-enforcement suites green against real PostgreSQL
- [ ] ArchUnit suite with an empty-import canary
- [ ] Idempotency registry with payload fingerprints on every creating operation
- [ ] Error catalog in `api.md` with codes, reasons and `details` keys, and the
      `ErrorCodeCatalogTest` that keeps it honest in both directions
- [ ] Outbox and relay, if the service publishes
- [ ] Health/readiness, startup summary, and metrics for every stated alarm
- [ ] CI provisions the database and roles
- [ ] README with a doc index and an honest **Known limitations** section
- [ ] CODEOWNERS entry, if the service is money-path
