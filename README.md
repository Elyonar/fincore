# fincore

**Open-source core banking for Africa.** Cloud-native, API-first, built for the
institutions global vendors underserve: microfinance banks, cooperatives, payment
service banks, and licensed fintechs — starting with Nigeria.

> You have the license. We have the technology.

fincore is a pure software platform. Institutions run it under **their own**
regulatory licenses and banking relationships; money never moves through us.

## Status

🚧 **Early build — being developed in public.** Three deployables exist and are
tested against real PostgreSQL: the **Ledger**, **Core** (customer, product and
transaction orchestration), and **Notification**, the platform's first event
consumer. All are pre-1.0 and **not production-ready** — each service's README
carries the honest list of what is still missing, and nothing here processes
real money yet. No payment rails connector exists, so money moves only between
accounts within one institution.

Per-service status lives in the table below, and canonically in each service's
`docs/CHANGELOG.md` — if the two ever disagree, the changelog is right.

Read the [ADRs](docs/adr/), then try to break the ledger —
[the test suite is the point](services/ledger/docs/testing.md).

## The constitution (short form)

1. **The ledger owns truth.** No service writes balances except the Ledger Service.
2. **Double-entry, immutable, idempotent.** Corrections via reversing entries only.
3. **Customers' money moves under customers' licenses.**
4. **A deployable owns its database. No shared databases, ever.** Modules inside one own schemas.
5. **Event-driven backbone.** Services publish domain events; consumers subscribe.
6. **Configuration over code.** Products, fees, interest, limits are tenant config.
7. **Multi-tenant by default.**
8. **Data residency aware; offline-tolerant edge apps.**
9. **Real-time, no batch windows.**
10. **Security by default.** RBAC + maker-checker, encryption, tamper-evident audit.
11. **Architect for Africa, build for Nigeria, expand one country at a time.**
12. **AI advises, humans decide, the ledger obeys only deterministic rules.**

## Where to find things

**Start here, depending on why you came:**

| I want to… | Go to |
|---|---|
| Understand what fincore is and why it exists | [`docs/prd.md`](docs/prd.md) — the product requirements doc |
| See why the big technical choices were made | [`docs/adr/`](docs/adr/) — Architecture Decision Records |
| Read the design of the first service | [`services/ledger/README.md`](services/ledger/README.md) — and its [`docs/`](services/ledger/docs/) |
| Contribute code or docs | [`.github/CONTRIBUTING.md`](.github/CONTRIBUTING.md) + [`.github/CLA.md`](.github/CLA.md) |
| Report a security issue | [`.github/SECURITY.md`](.github/SECURITY.md) |
| Work on this repo with an AI agent | [`AGENTS.md`](AGENTS.md) — the canonical memory map |

### Services

| Service | What it does | Status | Docs |
|---|---|---|---|
| **Ledger** | Double-entry posting engine — the single source of monetary truth. Accounts, entries, balances, holds. | ✅ Design AGREED v1.8 · implemented (pre-1.0) | [README](services/ledger/README.md) · [design](services/ledger/docs/design.md) · [data model](services/ledger/docs/data-model.md) · [architecture](services/ledger/docs/architecture.md) · [API](services/ledger/docs/api.md) · [posting algorithm](services/ledger/docs/posting-algorithm.md) · [testing](services/ledger/docs/testing.md) |
| **Core** | One deployable, three modules — `core/customer`, `core/product`, `core/orchestration`. Owns sagas, fee application and limits; the only caller of the Ledger's write API. | ✅ Design AGREED v1.11 · implemented (pre-1.0) | [README](services/core/README.md) · [design](services/core/docs/design.md) · [outcome protocol](services/core/docs/outcome-protocol.md) · [saga protocol](services/core/docs/saga-protocol.md) · [data model](services/core/docs/data-model.md) · [API](services/core/docs/api.md) · [testing](services/core/docs/testing.md) |
| Identity | Keycloak, self-hosted: auth, tenants, roles, maker-checker. Configured, not built. | Planned | — |
| **Notification** | The platform's first event consumer. Turns Core's business events into messages over a registry of channels; writes no money and holds no gateway credentials. | ✅ Design AGREED v1.2 · implemented (pre-1.0) | [README](services/notification/README.md) · [design](services/notification/docs/design.md) · [architecture](services/notification/docs/architecture.md) · [data model](services/notification/docs/data-model.md) · [API](services/notification/docs/api.md) · [testing](services/notification/docs/testing.md) |
| Lending · Compliance · Connectors | Further domains around the ledger. | Planned | — |

A **deployable** owns its own process and database; a **module** inside one owns
a schema and is reached only through its interface, never its tables
(PRD §3.4). New deployables get a row here when they land.

### Decision records

| ADR | Decision |
|---|---|
| [0001](docs/adr/0001-java-lts-spring-boot.md) | Java 25 LTS + Spring Boot for all domain services |
| [0002](docs/adr/0002-maven-monorepo.md) | Maven multi-module monorepo |
| [0003](docs/adr/0003-agpl-cla-open-from-day-one.md) | AGPL-3.0 + CLA, open from day one |
| [0004](docs/adr/0004-ledger-first.md) | Build the ledger first |
| [0005](docs/adr/0005-kafka-event-backbone.md) | Broker-agnostic event backbone, Kafka recommended |
| [0006](docs/adr/0006-modular-core.md) | Customer, Product and Orchestration ship as one Core deployable |
| [0007](docs/adr/0007-tenant-isolation-pattern.md) | Tenant isolation is a platform pattern, not a per-service invention |
| [0008](docs/adr/0008-event-contract.md) | One event envelope for the whole platform |
| [0009](docs/adr/0009-service-to-service-identity.md) | Authenticated service callers; the ledger enforces its own allowlist |
| [0010](docs/adr/0010-keycloak-realm-per-tenant.md) | One Keycloak realm per tenant, and identity lands before Core |
| [0011](docs/adr/0011-first-consumer-before-phase-three.md) | The platform's first event consumer is built before Phase 3 |

## Repository layout

```
fincore/
├── services/          # independently deployable Spring Boot services
│   ├── ledger/        # the first deployable — double-entry posting engine
│   │   ├── README.md  # the service's own map: purpose, boundaries, doc index
│   │   └── docs/      # the service's design & deep-dive docs
│   ├── core/          # one deployable, three modules:
│   │                  #   customer · product · orchestration · app
│   └── notification/  # the first event consumer — messages, not money
├── libs/              # shared internal libraries (auth, events) — arrive when needed
├── docs/
│   ├── prd.md         # product requirements
│   ├── adr/           # Architecture Decision Records (cross-cutting only)
│   ├── conventions/   # commits, design amendments, the service scaffold
│   └── README.md      # documentation conventions
├── .github/           # CONTRIBUTING, CLA, SECURITY, CI workflows
├── AGENTS.md          # memory map for AI agents & new contributors
├── pom.xml            # Maven multi-module root (Java 25 LTS)
└── compose.yaml       # local dev stack: PostgreSQL, Kafka, RabbitMQ, Keycloak,
                       #   and all three deployables
```

A monorepo is not a monolith: each directory under `services/` builds its own
container and owns its own database. Where a deployable holds several modules,
each module owns a schema and its own database role and reaches the others only
through their published interfaces — never their tables (PRD §3.4,
[ADR 0006](docs/adr/0006-modular-core.md)). Every service documents itself (`README.md` + `docs/`
inside the module); the root `docs/` holds only cross-cutting material. The
service README is the navigator for its own docs — this README links to the
service, not past it.

## Development approach

fincore is built **AI-assisted, human-decided, in public**. AI tools help write
code and documentation; every design decision is recorded in an ADR, every
service design is agreed before implementation, and nothing merges into
money-touching code without the invariant, property-based, and concurrency test
suites green. The test suite — not the author — is the argument for
correctness, and it's public: clone the ledger and try to break it. The build
journey is documented openly (videos and posts) as part of the project's
trust-first philosophy.

## Building

```bash
./mvnw verify        # build everything, run all tests
```

Requires Java 25. Local PostgreSQL comes up automatically in dev via
`compose.yaml` (Spring Boot docker-compose support).

## License

[AGPL-3.0-only](LICENSE) for the platform. SDKs and client libraries (when they
exist) will be Apache-2.0. Contributions require a signed [CLA](.github/CLA.md) — see
[CONTRIBUTING.md](.github/CONTRIBUTING.md).
