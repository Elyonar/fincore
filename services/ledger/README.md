# Ledger Service

> **The crown jewel.** The single source of monetary truth for the fincore
> platform. Every kobo that moves through any channel — teller, USSD, agent,
> API — ends its journey as entries here. If the ledger is wrong, nothing else
> matters; if the ledger is right, everything else is recoverable.

A **multi-tenant, double-entry posting engine** on PostgreSQL. It accepts
complete, balanced transactions and records them immutably. It knows four
nouns — accounts, entries, balances, holds — and one verb: post. It is
deliberately ignorant of fees, products, loans, and channels; those are other
services' stories.

**Built first because:** it is the only service with zero dependencies (calls
nothing, consumes no events) while everything depends on it — and it is the
project's trust artifact: a public test suite that provably conserves money.
([ADR 0004](../../docs/adr/0004-ledger-first.md))

**Status: design AGREED v1.3 — implemented, pre-1.0.** All sixteen documented
endpoints exist and 205 tests pass on CI against real PostgreSQL. It is **not
production-ready**: see [Known limitations](#known-limitations) below, which is
the honest list rather than the hopeful one. Changes to the agreed design go through
[`docs/CHANGELOG.md`](docs/CHANGELOG.md), never a silent doc edit.

---

## Memory map — where everything lives

Read in this order for full context; jump directly if you know what you need.

| You want to know… | Read | Status |
|---|---|---|
| The design at a glance + the decision log | [`docs/design.md`](docs/design.md) | AGREED v1.3 |
| Tables, relationships, ER diagram, schema-enforced rules, decided edge cases | [`docs/data-model.md`](docs/data-model.md) | AGREED v1.3 |
| Boundaries, traffic, the outbox/relay contract, DR posture | [`docs/architecture.md`](docs/architecture.md) | AGREED v1.3 |
| The endpoint surface, error catalog, and contract properties | [`docs/api.md`](docs/api.md) | AGREED v1.3 |
| How postings/reversals/holds execute: the two-tier lock protocol, hot accounts | [`docs/posting-algorithm.md`](docs/posting-algorithm.md) | AGREED v1.3 |
| The six invariants, the exposure split, and the test suites gating merges | [`docs/testing.md`](docs/testing.md) | AGREED v1.3 |
| Every amendment since the design was agreed | [`docs/CHANGELOG.md`](docs/CHANGELOG.md) | v1.3 |
| Platform-wide hard rules (no floats, append-only, one writer…) | [`AGENTS.md`](../../AGENTS.md) | Standing |
| Why Java 25 / monorepo / AGPL / ledger-first | [`docs/adr/`](../../docs/adr/) (root) | Accepted |
| Contribution process & CLA | [`CONTRIBUTING.md`](../../.github/CONTRIBUTING.md) | Standing |

New deep-dive topics get their own file under `docs/` and a row in this table
— the README stays the map, never the territory.

## Quick facts

| | |
|---|---|
| Language / framework | Java 25 LTS, Spring Boot |
| Storage | PostgreSQL 16 (own database — never shared) |
| Money representation | integer minor units (kobo/cents); floats forbidden |
| Writers | Transaction Orchestration **only**; Reporting read-only |
| Sync outbound calls | none, ever |
| Events | emits 6 via transactional outbox; consumes **none** |
| Latency / throughput targets | posting commit p99 < 200 ms; 200 TPS/tenant cluster |

## Run

```bash
# whole stack in Docker — database + service
docker compose up --build
curl http://localhost:8080/actuator/health/readiness   # {"status":"UP"}
open  http://localhost:8080/docs                       # interactive API

# database only, running the service from an IDE
docker compose up -d postgres
./mvnw -pl services/ledger spring-boot:run

# this service's tests — requires the database above to be running
docker compose up -d postgres
./mvnw -pl services/ledger verify
```

Requires Java 25 and Docker. The database publishes on host port **55432**, not
5432 — a clash with an unrelated local Postgres should never be able to fail
this stack. Override with `FINCORE_POSTGRES_PORT` if you want the usual port.

**Two database roles, deliberately.** Migrations run as the owner (`fincore`);
the service and the test suite connect as `ledger_app`, which is neither a
superuser nor `BYPASSRLS`. PostgreSQL skips row-level security entirely for
those, so connecting as the owner would leave every tenant-isolation policy
inert while every catalog check still reported it enabled. `db/init/` creates
the role locally; CI creates it in a workflow step; production provisions it
with a real secret.

## Events

The relay publishes to whichever backbone `ledger.events.broker` names — the
choice is a deployment setting, not a code dependency ([ADR 0005](../../docs/adr/0005-kafka-event-backbone.md)).

| Value | Behaviour |
|---|---|
| `kafka` *(default in compose)* | One topic per event type, e.g. `fincore.ledger.posting.completed`, keyed on `aggregateId` |
| `rabbit` | Topic exchange `fincore.ledger`, routing key = event type, `aggregateId` and `outboxId` as headers |
| `log` *(default with no config)* | **Delivers nothing.** A development adapter; the startup banner warns when it is active |

```bash
docker compose up                                        # Kafka
FINCORE_EVENTS_BROKER=rabbit docker compose --profile rabbit up   # RabbitMQ
```

Delivery is at-least-once and **consumers must deduplicate on `outboxId`**.
Ordering is per aggregate, never global — react to an event by fetching current
state through the read API, never by replaying a sequence.

**Swagger UI** is at [`/docs`](http://localhost:8080/docs); the raw document is at
`/v3/api-docs` (that `v3` is the *OpenAPI specification* version — this API is
`/v1`). `/swagger-ui/index.html` still works, because tooling expects it.
It is generated from the code, so it cannot describe an endpoint the service
does not serve — `docs/api.md` stays the agreed *design*, this is its executable
reflection. Every call needs an `X-Tenant-Id` header; the UI offers a field for
it.

The actuator surface is deliberately limited to `health` and `info`.

The image is a `jlink`-trimmed runtime on Alpine — **168 MB** carrying 25 JDK
modules rather than a stock JRE's full set. Adding a dependency that needs a
module not in that list fails at container start, so the `--add-modules` list in
the [`Dockerfile`](Dockerfile) is part of the build contract, not an
optimisation to be tuned casually.

## Module layout

```
services/ledger/
├── README.md      ← you are here — the navigator
├── docs/          ← the territory: one topic per file, indexed above
├── Dockerfile     ← multi-stage image; jlink runtime on Alpine
├── pom.xml        ← deliberately small; the dependency list is an architectural control
└── src/
    ├── main/java/org/elyonar/fincore/ledger/
    │   └── package-info.java     ← how this service is packaged, and why
    ├── main/resources/
    │   ├── application.yml
    │   └── db/migration/         ← Flyway, append-only: V1__initial_schema.sql
    └── test/java/org/elyonar/fincore/ledger/
        ├── support/              ← shared test infrastructure (database base class)
        ├── architecture/         ← ArchUnit: the hard rules as failing builds
        └── schema/               ← the schema exists (presence) and bites (enforcement)

Packages are **vertical slices named after the domain** — `account`, `posting`,
`hold`, `period`, `outbox`, `invariant`, `tenant`, `currency`, `shared` — not
`controller`/`service`/`repository` layers. One capability lives in one
directory. See
[`package-info.java`](src/main/java/org/elyonar/fincore/ledger/package-info.java).
```

## Known limitations

Real, and deliberately listed where a reader will find them rather than left to
be discovered.

| Area | State |
|---|---|
| Property-based tests | **Not implemented.** `docs/testing.md` marks the suite PLANNED |
| Failure injection, migration equivalence, restore drills | **Not implemented** — marked PLANNED |
| Hot-account throughput benchmark | **Not implemented**, so no TPS floor gates anything |
| Event delivery | Working. Kafka by default, RabbitMQ supported, `log` adapter delivers nothing (see below) |
| Authentication | Tenant arrives in a header. **This is not authentication** — Identity does not exist yet |
| Caller authorization | `api.md` names allowed callers; the ledger does not enforce them |
| Performance / RPO targets | Documented, **never measured**. No benchmark, soak or DR evidence |
| Release | `0.0.1-SNAPSHOT`. No tag, no published artifact, no upgrade policy |

Correctness mechanisms — append-only entries, forced row-level security under a
restricted role, the two-tier lock protocol, idempotency, invariants — are
implemented and tested. Operational maturity is not there yet, and the two are
different claims.

## Contributing here

Start with [`docs/design.md`](docs/design.md). The design is AGREED, so the
most valuable contribution now is *trying to break the ledger and encoding the
attack as a test*. Design critique is still welcome — bring a concrete
scenario the current design handles wrongly, and it becomes an amendment in
[`docs/CHANGELOG.md`](docs/CHANGELOG.md) rather than a silent edit.

Hard rules live in [`AGENTS.md`](../../AGENTS.md); how an agreed design is
amended in
[`design-changes.md`](../../docs/conventions/design-changes.md); process and
CLA in [`CONTRIBUTING.md`](../../.github/CONTRIBUTING.md). License:
[AGPL-3.0-only](../../LICENSE).
