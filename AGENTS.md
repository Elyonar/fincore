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
Each module under `services/` is an independently deployable service owning its
own database. A monorepo is NOT a monolith: never share databases or import
across service boundaries.

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

- Every service has its own `README.md`. Large services split into
  `docs/<topic>.md` files referenced from that README — one README never holds
  everything, and the root is never littered with service detail.
- Designs are agreed before implementation: a service's `docs/design.md` is
  DRAFT until marked AGREED; no domain code lands while DRAFT.
- **Once AGREED, a design is versioned and changes only by amendment.** Every
  design doc in the service carries the same version
  (`AGREED v1.0 (date) — amendments via CHANGELOG.md`), and every change to
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

Rules 1, 2, 5 and the ledger's boundaries are **enforced mechanically** — by
ArchUnit in `services/ledger/src/test/java/.../architecture/HardRulesTest.java`
and by database triggers. Prose persuades a careful contributor; a red build
stops a careless one. When you find a rule here that could be a test and is
not, that gap is a bug in the guardrails.


1. Money amounts are integer minor units (kobo/cents). No float/double ever
   touches a money value.
2. Ledger entries are append-only; corrections are reversing entries.
3. Only the Ledger Service writes balances. Only Orchestration calls the
   ledger's write API.
4. Every money-writing operation is idempotent via caller-supplied idempotency
   keys.
5. Database per service. No cross-service imports; services talk via APIs and
   events only.
6. Multi-tenancy: `tenant_id` scoping on every query.
7. AI advises, humans decide. No change to ledger or orchestration code merges
   without the invariant, property-based, and concurrency suites green, and
   money-touching changes ship with the tests that prove them. This applies to
   **every** contributor — nothing reveals whether a diff was AI-generated, so
   a rule that only bound AI authors would bind no one, while implying
   hand-written ledger code needs less proof. It does not.
8. Never commit security-sensitive specifics: credentials, real auth flows,
   fraud thresholds, deployment infrastructure details.

## Build & test

```bash
./mvnw verify                 # whole platform
./mvnw -pl services/ledger verify   # one service
```

Local PostgreSQL: `compose.yaml` (Spring Boot docker-compose starts it in dev).
CI: `.github/workflows/ci.yml` — every push/PR runs `./mvnw verify`.

## Current state

- `services/ledger` — **design AGREED v1.0 (2026-08-04); implementation open.**
  Build in this order, because it front-loads the riskiest claims:
  `V1__initial_schema.sql` + schema-presence tests → posting happy path and
  idempotency → the concurrency suite (the two-tier lock protocol and the
  zero-deadlock claim are the most likely thing to be wrong, and the cheapest
  to falsify early) → holds, reversal, compensation → outbox and relay →
  invariants and anchors last. The MVCC quiesce horizon is spiked standalone
  before anchors or the relay depend on it.
- Any change to the ledger contract now goes through
  `services/ledger/docs/CHANGELOG.md` — never a silent doc edit.
- `libs/` — intentionally empty; extract a lib only when a second consumer
  exists.
