# AGENTS.md — memory map for AI agents (and fast-moving humans)

This repository is developed AI-assisted with human review. This file is the
entry point an agent should read first; it says where truth lives and which
rules are non-negotiable.

Other tools are pointed here rather than given their own copy of the rules:
`CLAUDE.md` (Claude Code), `.github/copilot-instructions.md` (Copilot), and
`.cursor/rules/fincore.mdc` (Cursor) are thin redirects. One source of truth,
so the guardrails cannot drift apart per tool.

## What this project is

`fincore` — open-source (AGPL-3.0) core banking platform for Africa,
Nigeria-first. Maven multi-module monorepo, Java 25 LTS, Spring Boot.

Each directory under `services/` is a **deployable**: its own process, its own
database, its own release cycle. A deployable may hold several **modules** — one
domain each, one schema each, reached only through their published interfaces.

A monorepo is NOT a monolith: never share a database across deployables, never
import across a deployable boundary, and never read another module's tables.
Vocabulary and the rules that follow: PRD §3.4. Current packaging:
[ADR 0006](docs/adr/0006-modular-core.md).

## Where truth lives (read in this order)

1. `docs/prd.md` — the PRD, the platform's "sacred guide": vision,
   constitution, service decomposition, build phases. Changes only via PR with
   a version bump.
2. `docs/adr/` — recorded decisions. Never contradict an accepted ADR; propose a
   new ADR to supersede it. An accepted ADR overrides an older PRD section
   until the PRD is revised.
3. `services/<name>/README.md` — each service's own map: purpose, boundaries,
   API, events, and links into its `docs/`.
4. `services/<name>/docs/design.md` — the agreed design for that service, plus
   `docs/CHANGELOG.md` for every amendment since it was agreed. Code that
   contradicts an agreed design is wrong even if it works; read the changelog
   before assuming a doc is current.
5. Root `README.md` — public-facing overview only. Root `docs/` holds only
   cross-cutting material (PRD, ADRs, conventions); service-specific docs live
   inside the service.

## Documentation convention

- **Starting a service? Read
  [`docs/conventions/service-scaffold.md`](docs/conventions/service-scaffold.md)
  first.** It is the checklist of what every service must have — tenancy,
  roles, migrations, idempotency, outbox, tests — promoted from what building
  the ledger taught. Skipping an item is a decision to be stated, not an
  omission to be discovered.
- Every service has its own `README.md`. Large services split into
  `docs/<topic>.md` files referenced from that README — one README never holds
  everything, and the root is never littered with service detail.
- Designs are agreed before implementation: a service's `docs/design.md` is
  DRAFT until marked AGREED; no domain code lands while DRAFT.
- **Once AGREED, a design is versioned and changes only by amendment.** Every
  design doc in the service carries the same version
  (`AGREED vX.Y (date) — amendments via CHANGELOG.md`), and every change to
  the contract is recorded in `services/<name>/docs/CHANGELOG.md`. Full rules
  and entry format: [`docs/conventions/design-changes.md`](docs/conventions/design-changes.md).
  In short: design lands before code in its own PR; code that contradicts an
  AGREED doc is a bug, never a reason to quietly edit the doc; superseded
  decisions are marked, not deleted; anything forcing another service to
  change needs an ADR as well.
- Decisions of record → `docs/adr/` (numbered, immutable, superseded not edited).
  **ADRs are platform law; service changelogs are service history** — a
  cross-cutting decision goes to an ADR, a change to one service's own
  contract goes to its CHANGELOG.
- Commits follow Conventional Commits per `docs/conventions/commits.md`:
  `<type>(<scope>): <subject>` — scopes are module names (`ledger`, `repo`,
  `adr`, `docs`, `libs`, `deps`). Money-touching commits ship with their tests
  and name the invariants they affect. One logical change per commit.

## Hard rules (violations fail the build where possible, review otherwise)

Rules 1, 2, 5, 9 and the ledger's boundaries are **enforced mechanically** — by
ArchUnit in `services/ledger/src/test/java/.../architecture/HardRulesTest.java`,
by `ErrorCodeCatalogTest` in the same package, and by database triggers. Prose persuades a careful contributor; a red build
stops a careless one. When you find a rule here that could be a test and is
not, that gap is a bug in the guardrails.


1. Money amounts are integer minor units (kobo/cents). No float/double ever
   touches a money value.
2. Ledger entries are append-only; corrections are reversing entries.
3. Only the Ledger Service writes balances. Only the Orchestration domain calls
   the ledger's write API — today that is the `core/orchestration` module, the
   only module permitted to declare the ledger client. Enforced by the
   classpath, by ArchUnit, and by the ledger's own caller allowlist
   ([ADR 0009](docs/adr/0009-service-to-service-identity.md)).
4. Every money-writing operation is idempotent via caller-supplied idempotency
   keys.
5. A deployable owns its database; a module owns a schema. No shared databases
   and no imports across a deployable boundary — deployables integrate via APIs
   and events. No module reads another module's tables — modules integrate via
   interfaces, and the boundary is enforced by per-module database roles
   ([ADR 0006](docs/adr/0006-modular-core.md)).
6. Multi-tenancy: `tenant_id` scoping on every query, with forced row-level
   security under a restricted role and `SET LOCAL` tenant context as the
   backstop ([ADR 0007](docs/adr/0007-tenant-isolation-pattern.md)).
7. AI advises, humans decide. No change to the ledger or to `core/orchestration`
   merges
   without the invariant, property-based, concurrency and failure-injection
   suites green, and money-touching changes ship with the tests that prove them.
   All four exist and run; `testing.md` marks every suite IMPLEMENTED, PARTIAL
   or DEFERRED, and a rule may only name a suite that can actually run. This applies to
   **every** contributor — nothing reveals whether a diff was AI-generated, so
   a rule that only bound AI authors would bind no one, while implying
   hand-written ledger code needs less proof. It does not.
8. Never commit security-sensitive specifics: credentials, real auth flows,
   fraud thresholds, deployment infrastructure details.
9. Errors are machine-readable before they are readable. Every rejection carries
   a documented `code`, a `reason` where one code spans several causes, and
   `details` holding the facts a message interpolates — never English prose a
   caller would have to parse. The `message` field is developer English for a
   log and is never shown to an end user, because a service serving Lagos and
   Abidjan cannot write text for either. Full rules and the per-service
   catalog test: [`docs/conventions/error-contract.md`](docs/conventions/error-contract.md).
10. Avoid string literals for anything referenced twice — property keys, status
   values, error codes. They belong in a constant or an enum, so changing one
   is one edit and not a search. Enforced by review and by deriving docs and
   tests from the enums rather than restating them.

## Build & test

```bash
./mvnw verify                 # whole platform
./mvnw -pl services/ledger verify   # one service
```

Local PostgreSQL: `compose.yaml` (Spring Boot docker-compose starts it in dev).
CI: `.github/workflows/ci.yml` — every push/PR runs `./mvnw verify`.

## Current state

**Canonical status lives in `services/<name>/docs/CHANGELOG.md`.** This section
says where things stand; the changelog says what changed and when. If the two
ever disagree, the changelog is right and this section is stale — and it has
been stale twice, both times claiming less had been built than actually had. If
you are about to build something this section says does not exist, check the
service directory first.

Verified by running the suites, not by reading the docs: **521 tests green** —
26 `libs/auth`, 9 `libs/events`, 231 ledger, 198 Core, 57 Notification.

- `services/ledger` — **design AGREED v1.9; implemented and merged to main.**
  Every documented endpoint exists. Do not re-implement the schema, posting
  engine, holds, reversal, outbox, value dating, statements or invariants: they
  are done.

  Known gaps, all tracked in `services/ledger/docs/testing.md` with explicit
  status markers — these are the honest edges, not hidden work:
  - the invariant, property-based, concurrency and failure-injection suites are
    green, so ADR 0004's precondition for starting a second service is **met**
  - events are delivered for real: Kafka by default, RabbitMQ supported,
    selected by `ledger.events.broker` (ADR 0005). The `log` adapter delivers
    nothing and the startup banner says so
  - tenant identity arrives in a header until Identity exists; it is **not
    authentication**, and the ledger does not enforce the caller roles `api.md`
    names
  - deferred with reasons in `testing.md`: hot-account throughput benchmark,
    migration equivalence, expand/migrate/contract rehearsal, restore drills,
    and two cross-tenant probes
  - no performance, soak or disaster-recovery evidence exists

- `services/core` — **design AGREED v1.13; implemented, merged, and running.**
  One deployable holding four modules ([ADR 0006](docs/adr/0006-modular-core.md)):
  `customer`, `product`, `orchestration` and `app`, with a schema and a database
  role each. Transfers, cash in and out, business reversal with maker-checker
  approval, customer contact and consent, product versioning with publish
  control. It has its own image and compose service, and calls the ledger over
  HTTP.

  `customer` and `product` carry no unit tests of their own; both are covered by
  `app`'s integration suite, which is why Core's 193 sit in two modules.

  Read `services/core/docs/design.md` and then `outcome-protocol.md` before
  touching anything here. The rule that matters most: **an unknown outcome is
  never compensated and never reported as success** — it is a 503, the same key
  is retried, and the worker resolves it.

- `services/notification` — **design AGREED v1.4; implemented, merged, and
  running.** The platform's first event *consumer*, taken ahead of its PRD phase
  for the reasons in [ADR 0011](docs/adr/0011-first-consumer-before-phase-three.md):
  nothing had ever consumed an event, and designing a consumer immediately found
  that the two publishers were emitting different envelopes despite ADR 0008
  mandating one — fixed before this service was built.

  One schema, six tables, two database roles, its own image and compose service.
  It consumes `transfer.completed`, resolves contact and consent from Core, and
  queues a message per side of a transfer. **Every consumed event ends as a
  message or as a suppression carrying a reason code** — that is the guarantee
  to preserve if you touch it.

  Known gaps, tracked with status markers in
  `services/notification/docs/testing.md`:
  - **nothing reaches a customer.** The `log` senders deliver nowhere and say so
    at startup; the messaging connector that would make them real is not built,
    by decision — connectors come last
  - no error-catalog test and no metrics. The scaffold asks for both and
    `testing.md` marks them PLANNED rather than implying coverage
  - delivery receipts, bounce handling and monetary cost need a gateway to test
    against, so they arrive with the connector

- `libs/` — two libraries, each extracted only once a second consumer existed:
  `auth` (token validation, identity context, `require` helpers — ADR 0009) and
  `events` (the Kafka/RabbitMQ/logging publishers behind one seam, and ADR 0008's
  envelope renderer — ADR 0005, 0008). Neither owns a database or a process,
  which is what makes them libraries rather than deployables (PRD §3.4).

  Consumer-side machinery — dedupe on `(publisher, eventId)`, epoch fencing,
  stale-event rejection — is being built in Notification first and moves here
  when a second consumer arrives, per the standing rule that a library follows
  the second consumer rather than anticipating it.
