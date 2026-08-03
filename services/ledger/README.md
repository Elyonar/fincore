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

**Status: design AGREED v1.0 (2026-08-04) — implementation open.** The
skeleton compiles; domain code lands next, starting with the schema and its
presence tests. Changes to the agreed design go through
[`docs/CHANGELOG.md`](docs/CHANGELOG.md), never a silent doc edit.

---

## Memory map — where everything lives

Read in this order for full context; jump directly if you know what you need.

| You want to know… | Read | Status |
|---|---|---|
| The design at a glance + the decision log | [`docs/design.md`](docs/design.md) | AGREED v1.0 |
| Tables, relationships, ER diagram, schema-enforced rules, decided edge cases | [`docs/data-model.md`](docs/data-model.md) | AGREED v1.0 |
| Boundaries, traffic, the outbox/relay contract, DR posture | [`docs/architecture.md`](docs/architecture.md) | AGREED v1.0 |
| The endpoint surface, error catalog, and contract properties | [`docs/api.md`](docs/api.md) | AGREED v1.0 |
| How postings/reversals/holds execute: the two-tier lock protocol, hot accounts | [`docs/posting-algorithm.md`](docs/posting-algorithm.md) | AGREED v1.0 |
| The six invariants, the exposure split, and the test suites gating merges | [`docs/testing.md`](docs/testing.md) | AGREED v1.0 |
| Every amendment since the design was agreed | [`docs/CHANGELOG.md`](docs/CHANGELOG.md) | v1.0 |
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

# database only, running the service from an IDE
docker compose up -d postgres
./mvnw -pl services/ledger spring-boot:run

# this service's tests
./mvnw -pl services/ledger verify
```

Requires Java 25 and Docker. The database publishes on host port **55432**, not
5432 — a clash with an unrelated local Postgres should never be able to fail
this stack. Override with `FINCORE_POSTGRES_PORT` if you want the usual port.

Domain endpoints appear as implementation lands; today the service serves only
its actuator health probes, and the actuator surface is deliberately limited to
`health` and `info`.

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
├── Dockerfile     ← multi-stage image; built from the repo root
├── pom.xml        ← JDBC/Flyway arrive with the first schema commit
└── src/
    ├── main/java/org/elyonar/fincore/ledger/  ← implementation (skeleton)
    ├── main/resources/                         ← config; db/migration/ (Flyway)
    └── test/java/                              ← suites described in docs/testing.md
```

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
