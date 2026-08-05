# fincore

**Open-source core banking for Africa.** Cloud-native, API-first, built for the
institutions global vendors underserve: microfinance banks, cooperatives, payment
service banks, and licensed fintechs — starting with Nigeria.

> You have the license. We have the technology.

fincore is a pure software platform. Institutions run it under **their own**
regulatory licenses and banking relationships; money never moves through us.

## Status

🚧 **Early build — being developed in public.** The Ledger design is **AGREED
v1.0** and implementation has begun, starting with the schema and its test
suite. Nothing here processes real money yet.

Watch the journey, read the [ADRs](docs/adr/), and when the ledger lands, try
to break it — [the test suite is the point](services/ledger/docs/testing.md).

## The constitution (short form)

1. **The ledger owns truth.** No service writes balances except the Ledger Service.
2. **Double-entry, immutable, idempotent.** Corrections via reversing entries only.
3. **Customers' money moves under customers' licenses.**
4. **Database per service. No shared databases, ever.**
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
| **Ledger** | Double-entry posting engine — the single source of monetary truth. Accounts, entries, balances, holds. | ✅ Design AGREED v1.3 · implemented (pre-1.0) | [README](services/ledger/README.md) · [design](services/ledger/docs/design.md) · [data model](services/ledger/docs/data-model.md) · [architecture](services/ledger/docs/architecture.md) · [API](services/ledger/docs/api.md) · [posting algorithm](services/ledger/docs/posting-algorithm.md) · [testing](services/ledger/docs/testing.md) |
| Transaction Orchestration | The only writer to the Ledger; owns sagas and workflow. | Planned | — |
| Identity | Auth, tenants, roles, maker-checker. | Planned | — |
| Product · Lending · Customer · Compliance | Domain services around the ledger. | Planned | — |

Each service is independently deployable and owns its own database. New
services get a row here when they land.

### Decision records

| ADR | Decision |
|---|---|
| [0001](docs/adr/0001-java-lts-spring-boot.md) | Java 25 LTS + Spring Boot for all domain services |
| [0002](docs/adr/0002-maven-monorepo.md) | Maven multi-module monorepo |
| [0003](docs/adr/0003-agpl-cla-open-from-day-one.md) | AGPL-3.0 + CLA, open from day one |
| [0004](docs/adr/0004-ledger-first.md) | Build the ledger first |

## Repository layout

```
fincore/
├── services/          # independently deployable Spring Boot services
│   └── ledger/        # the first service — double-entry posting engine
│       ├── README.md  # the service's own map: purpose, boundaries, doc index
│       └── docs/      # the service's design & deep-dive docs
├── libs/              # shared internal libraries (auth, events) — arrive when needed
├── docs/
│   ├── prd.md         # product requirements
│   ├── adr/           # Architecture Decision Records (cross-cutting only)
│   ├── conventions/   # commit format and other shared conventions
│   └── README.md      # documentation conventions
├── .github/           # CONTRIBUTING, CLA, SECURITY, CI workflows
├── AGENTS.md          # memory map for AI agents & new contributors
├── pom.xml            # Maven multi-module root (Java 25 LTS)
└── compose.yaml       # local dev dependencies (PostgreSQL)
```

A monorepo is not a monolith: each module under `services/` builds its own
deployable jar/container and owns its own database. Module boundaries are the
service boundaries. Every service documents itself (`README.md` + `docs/`
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
