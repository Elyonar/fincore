# Core Service

> **The conductor.** The Ledger knows *how* money moves and refuses to know
> *why*. Core knows why: it turns a business intent — "this teller is
> withdrawing ₦20,000 for customer Ada" — into a balanced, attributed,
> idempotent posting, and guarantees that a request interrupted at any point
> ends either completely done or completely undone. Never half.

One deployable, four domain modules, one database, four domain schemas, one
database role per module. It is the **only** caller of the Ledger's write API.

**Status: design AGREED v1.17 — implemented, pre-1.0.** Packaging is decided
([ADR 0006](../../docs/adr/0006-modular-core.md)), all documented endpoints are
served, and every suite runs against real PostgreSQL. Changes to the design are
amendments in [`docs/CHANGELOG.md`](docs/CHANGELOG.md), in their own PR ahead
of the code — never silent edits.

---

## Memory map — where everything lives

| You want to know… | Read | Status |
|---|---|---|
| The design at a glance + the decision log | [`docs/design.md`](docs/design.md) | AGREED v1.17 |
| **The three-valued outcome model** — the thing this service exists to get right | [`docs/outcome-protocol.md`](docs/outcome-protocol.md) | AGREED v1.17 |
| How sagas execute, recover, and are claimed across instances | [`docs/saga-protocol.md`](docs/saga-protocol.md) | AGREED v1.17 |
| Modules, boundaries, the ledger client, events, DR posture | [`docs/architecture.md`](docs/architecture.md) | AGREED v1.17 |
| Tables per schema, ownership rules, decided edge cases | [`docs/data-model.md`](docs/data-model.md) | AGREED v1.17 |
| Endpoint surface, error catalog, contract properties | [`docs/api.md`](docs/api.md) | AGREED v1.17 |
| Core's eight invariants and every test suite | [`docs/testing.md`](docs/testing.md) | AGREED v1.17 |
| **Lending — the fifth module**: origination, schedules, accrual, delinquency | [`docs/lending.md`](docs/lending.md) | AGREED v1.17 |
| Every amendment since the design was agreed | [`docs/CHANGELOG.md`](docs/CHANGELOG.md) | v1.16 |
| Platform hard rules | [`AGENTS.md`](../../AGENTS.md) | Standing |
| What every service must have before it ships | [`service-scaffold.md`](../../docs/conventions/service-scaffold.md) | Standing |

New deep-dive topics get their own file under `docs/` and a row here — the
README stays the map, never the territory.

## The modules

| Module | Schema | Owns |
|---|---|---|
| `customer` | `customer` | Profiles, KYC tier, lifecycle, mandates, customer↔account mapping. **The only schema holding PII.** |
| `product` | `product` | Product catalog, fee rules, limit rules, versioned configuration. Returns *decisions*, never postings. |
| `organization` | `organization` | The tenant's operational tree — branches, regions, business lines — and who is assigned where ([ADR 0012](../../docs/adr/0012-organizational-model.md)). Operational scope only: never a legal entity, booking unit or jurisdiction. |
| `lending` | `lending` | Origination, schedules, repayment allocation, accrual, delinquency ([ADR 0013](../../docs/adr/0013-lending-module-first.md)). Sits **above** orchestration; every money movement is a saga. |
| `orchestration` | `orchestration` | Sagas, limit reservations, fee application, tills, the ledger client. **The only module that may call the Ledger's write API.** |

`app` assembles them: wiring, the outbox relay, the saga worker. No domain
logic.

Boundaries are enforced three ways — database privilege (one role per schema,
no grants on its neighbours), ArchUnit (`api` is public, `internal` is not), and
the POM dependency graph. A boundary defended one way is a boundary that erodes.

## Run

```bash
# whole stack — database, ledger, core
docker compose up --build
curl http://localhost:58081/actuator/health   # {"status":"UP"}
open  http://localhost:58081/docs             # interactive API

# database only, running Core from an IDE
docker compose up -d postgres
./mvnw -pl services/core/app spring-boot:run
```

Core publishes on host port **58081**, not 8081, and the ledger on **58080** —
each service's own test suite binds its default port, so a running container
there would mean `docker compose up` and `./mvnw verify` could not coexist. Only
the host side moves; inside the compose network Core still calls
`http://ledger:8080`. Override with `FINCORE_CORE_PORT` and `FINCORE_LEDGER_PORT`.

**The suite runs against `core_test`, not `core`.** Moving the ports was the
visible half of letting the stack and the suite coexist; separating the database
was the other half. A running deployable is a concurrent writer — Core's saga
worker and outbox relay poll on short intervals, and because PostgreSQL
transaction ids are cluster-wide, that polling reached across into the *ledger's*
database and stalled its quiesce horizon. Two suites produced failures that
looked like real defects and were not. All six module datasources point at
`core_test`, and Flyway builds it from the same migrations, so the schemas cannot
drift apart.

Point them elsewhere with `SPRING_DATASOURCE_URL`. If your Postgres volume
predates this, the database will not exist, because `db/init` runs only on an
empty volume — either recreate it, or add it in place:

```bash
docker compose exec postgres psql -U fincore -d postgres \
  -c "CREATE DATABASE core_test OWNER fincore;"
```

**Four database roles, deliberately.** Migrations run as the owner; each module's
traffic connects as `core_customer`, `core_product` or `core_orchestration`,
granted on its own schema and nothing else, with `core_worker` and `core_relay`
for the background components. A cross-module query fails at runtime rather than
surviving until someone tries to extract a module. `db/init/` creates them
locally; CI creates them in a workflow step.

The startup banner reports which Ledger this instance will call, whether identity
verifies anything, and the role each module connected as — including whether
row-level security can constrain it, because a superuser or `BYPASSRLS` role
makes every tenant policy inert while the catalog still reports RLS enabled.

## Quick facts

| | |
|---|---|
| Language / framework | Java 25 LTS, Spring Boot |
| Storage | PostgreSQL (own database — three schemas, never shared with another deployable) |
| Money representation | integer minor units; floats forbidden, percentages are integer basis points |
| Calls out to | the Ledger, and nothing else in v1 |
| Events | publishes per module via transactional outbox; consumes **none in v1** |
| Identity | validated token from day one; tenant from the token, never a header |

## v1 scope

**In:** cash deposit, cash withdrawal, intra-tenant transfer, reversal,
transaction status lookup. Fees applied in the same ledger transaction as the
principal. Limits enforced as reservations. Customer and Product as real modules
with minimal v0 configuration.

**Out, deliberately:** inter-bank transfers and any rails connector, holds,
standing orders, bulk disbursement, interest accrual, lending, AML rules,
inbound events. The connector seam is designed in
[`docs/saga-protocol.md`](docs/saga-protocol.md) and not built.

**v1 places no holds.** A hold reserves funds across an external call whose
outcome is unknown; every v1 flow is a single atomic ledger posting with no
external call in between, so a hold would reserve funds against nothing. Said
plainly here because "we have holds" is the kind of claim that gets assumed
rather than checked.

## Why the saga machinery exists even for single-step flows

Not because the flow branches — because **the ledger call can time out**. An
unknown outcome needs persisted state, a deterministic retry key, and a recovery
worker regardless of how few steps precede it. That is the whole design in one
sentence, and [`docs/outcome-protocol.md`](docs/outcome-protocol.md) is the
document that makes it true.

## No human authorizes money movement here

Worth stating as a property of the whole design, because it was arrived at
rather than assumed. Every path that moves money is either:

- **automated and discretionless** — a saga posting what it decided, or
  compensating a posting it made itself after a `DEFINITE_FAILURE`; or
- **human and approved** — a business reversal carrying a single-use,
  amount-bound maker-checker approval.

There is no third kind. Notably, an operator cannot declare that an uncertain
transaction posted (`POST /v1/ops/cases/{id}/resolve` takes no outcome), and even
a Ledger restore is recovered by mechanical replay rather than judgement. An
earlier draft carried a human-decision path for the restore case; working through
it showed the reasoning was wrong, and it was removed.

## Open questions

None blocking — the seven carried through drafting are resolved in
[`docs/design.md`](docs/design.md)'s decision log. New ones land there and, now
that the design is AGREED, are resolved by amendment in
[`docs/CHANGELOG.md`](docs/CHANGELOG.md).

One limitation is recorded rather than solved: post-restore reconciliation is
operator-triggered in v1, because the Ledger stamps its epoch on published events
only and Core consumes none, so Core cannot detect that a restore happened.

## Contributing here

Start with [`docs/design.md`](docs/design.md), then
[`docs/outcome-protocol.md`](docs/outcome-protocol.md). The most valuable
contribution is a concrete scenario the design handles wrongly — particularly one
involving a partial failure or an unknown outcome. The design is AGREED, so a
successful challenge lands as an amendment in
[`docs/CHANGELOG.md`](docs/CHANGELOG.md) rather than a silent edit, and the
superseded decision is annotated rather than deleted.

Hard rules: [`AGENTS.md`](../../AGENTS.md). Amendment process:
[`design-changes.md`](../../docs/conventions/design-changes.md). Scaffold
requirements: [`service-scaffold.md`](../../docs/conventions/service-scaffold.md).
License: [AGPL-3.0-only](../../LICENSE).

## Known limitations

Real, and listed where a reader will find them rather than left to be
discovered. Per-suite status lives in [`docs/testing.md`](docs/testing.md).

| Area | State |
|---|---|
| Money paths | Deposit, withdrawal, intra-tenant transfer and business reversal only. **No rails connector**, so money moves only between accounts within one institution |
| Holds | Designed in `saga-protocol.md`, not built. They activate with the first connector, because a hold reserves funds across an external call and v1 has none |
| Compensating reversal | Same: v1's single-step sagas have nothing to undo. The rules are written; the path is not |
| Consumed events | None. Core is command-driven, so consumer-side dedupe and epoch fencing are not built here — Notification builds them first |
| Interest accrual | Not built, and deliberately unassigned for deposits. Product's rule model carries FLAT/PERCENT fees and PER_TXN/DAILY limits only |
| Identity | `libs/auth` validates tokens, but the deployed realm model is not provisioned; dev mode uses headers and announces itself |
| Reconciliation | Runs hourly against COMPLETED sagas (v1.14). The FAILED-saga half waits on a ledger read-by-key amendment; see `testing.md` |
| Fee account fallback | Published versions predating `fee_rules.fee_account_id` cannot be edited to carry it (published versions are immutable), so for those the caller-supplied account still applies. Ages out as versions are republished |
| Unit scope | Organizational units exist and are attributed (ADR 0012), but no endpoint yet *requires* one — unit-scoped authorization arrives with the teller application |
| Income recognition coverage | Recognition (v1.17) posts collected interest and penalties per repayment; versions without an income account resolve as a recorded no-op. Ages out as versions are republished with the account configured. Value-dated month-end postings wait on a ledger value-dating amendment |
| Loan funding account | `loan_rules.funding_account_id` (v1.17) is configuration-first; the caller-supplied account remains only a fallback for versions predating the column — the fee-account pattern, ageing out the same way |
| Performance | Targets in `architecture.md` are intent. Nothing has been benchmarked |
