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
| Read a service's design | its own `README.md` and `docs/` — [ledger](services/ledger/README.md), [core](services/core/README.md), [notification](services/notification/README.md) |
| Run the platform locally | [Running it](#running-it), below |
| Contribute code or docs | [`.github/CONTRIBUTING.md`](.github/CONTRIBUTING.md) + [`.github/CLA.md`](.github/CLA.md) |
| Report a security issue | [`.github/SECURITY.md`](.github/SECURITY.md) |
| Work on this repo with an AI agent | [`AGENTS.md`](AGENTS.md) — the canonical memory map |

### Services

| Service | What it does | Status | Docs |
|---|---|---|---|
| **Ledger** | Double-entry posting engine — the single source of monetary truth. Accounts, entries, balances, holds. | ✅ Design AGREED v1.9 · implemented (pre-1.0) | [README](services/ledger/README.md) · [design](services/ledger/docs/design.md) · [data model](services/ledger/docs/data-model.md) · [architecture](services/ledger/docs/architecture.md) · [API](services/ledger/docs/api.md) · [posting algorithm](services/ledger/docs/posting-algorithm.md) · [testing](services/ledger/docs/testing.md) |
| **Core** | One deployable, four domain modules — `core/customer`, `core/product`, `core/organization`, `core/orchestration`. Owns sagas, fee application, limits and the organizational tree (ADR 0012); the only caller of the Ledger's write API. Lending — the fifth module — is designed (ADR 0013) and not yet built. | ✅ Design AGREED v1.15 · implemented (pre-1.0) | [README](services/core/README.md) · [design](services/core/docs/design.md) · [outcome protocol](services/core/docs/outcome-protocol.md) · [saga protocol](services/core/docs/saga-protocol.md) · [data model](services/core/docs/data-model.md) · [API](services/core/docs/api.md) · [testing](services/core/docs/testing.md) |
| Identity | Keycloak, self-hosted: auth, tenants, roles, maker-checker. **Configured, never built** — it is commodity software, and the platform's side of it is [`libs/auth`](libs/auth/README.md), which every service imports. Keycloak runs in compose behind the `identity` profile with a seeded development realm (six job-shaped users, permission and `units` claims); without it, services default to a development identity that announces itself at startup | Partial | [ADR 0010](docs/adr/0010-keycloak-realm-per-tenant.md) |
| **Notification** | The platform's first event consumer. Turns Core's business events into messages over a registry of channels; writes no money and holds no gateway credentials. | ✅ Design AGREED v1.4 · implemented (pre-1.0) | [README](services/notification/README.md) · [design](services/notification/docs/design.md) · [architecture](services/notification/docs/architecture.md) · [data model](services/notification/docs/data-model.md) · [API](services/notification/docs/api.md) · [testing](services/notification/docs/testing.md) |
| Lending · Compliance · Connectors | Further domains around the ledger. | Planned | — |

A **deployable** owns its own process and database; a **module** inside one owns
a schema and is reached only through its interface, never its tables
(PRD §3.4). New deployables get a row here when they land.

**Shared libraries**, extracted only once a second consumer existed rather than
in anticipation of one: [`libs/auth`](libs/auth/README.md) — token validation,
identity context and the `require` helpers (ADR 0009); `libs/events` — the
Kafka/RabbitMQ/logging publishers behind one seam, and the one renderer of
ADR 0008's envelope. Neither owns a database or a process, which is what makes
them libraries rather than deployables (PRD §3.4).

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
| [0012](docs/adr/0012-organizational-model.md) | Organizational units are operational scope — never legal entity, booking unit or jurisdiction |
| [0013](docs/adr/0013-lending-module-first.md) | Lending starts as a Core module, above Orchestration; extraction triggers named |

## Repository layout

```
fincore/
├── services/          # independently deployable Spring Boot services
│   ├── ledger/        # the first deployable — double-entry posting engine
│   │   ├── README.md  # the service's own map: purpose, boundaries, doc index
│   │   └── docs/      # the service's design & deep-dive docs
│   ├── core/          # one deployable, four domain modules + assembly:
│   │                  #   customer · product · organization · orchestration · app
│   └── notification/  # the first event consumer — messages, not money
├── libs/              # shared internal libraries (auth, events) — arrive when needed
├── docs/
│   ├── prd.md         # product requirements
│   ├── adr/           # Architecture Decision Records (cross-cutting only)
│   ├── conventions/   # commits, design amendments, the service scaffold
│   └── README.md      # documentation conventions
├── keycloak/          # the seeded development realm — fake credentials, on purpose
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

## Running it

```bash
docker compose up --build          # PostgreSQL, Kafka, and all three deployables
```

| | Health | Interactive API |
|---|---|---|
| Ledger | http://localhost:58080/actuator/health/readiness | http://localhost:58080/docs |
| Core | http://localhost:58081/actuator/health/readiness | http://localhost:58081/docs |
| Notification | http://localhost:58082/actuator/health/readiness | http://localhost:58082/docs |

Host ports are deliberately not 8080–8082: each service's own test suite binds
its real port, and a container holding it would mean `docker compose up` and
`./mvnw verify` could not run at the same time. A stack should never be able to
break the build, or the other way round. Inside the compose network the ports
are unchanged.

RabbitMQ is available as an alternative backbone
(`FINCORE_EVENTS_BROKER=rabbit`).

### Logging in

Keycloak ships with a seeded development realm — six users shaped like real
jobs, so trying an endpoint your role does not cover and getting a 403 is the
system working.

```bash
docker compose --profile identity up -d keycloak          # admin console: localhost:8180 (admin/admin)

FINCORE_AUTH_MODE=jwt \
FINCORE_AUTH_ISSUER_URI=http://localhost:8180/realms/fincore-dev \
FINCORE_AUTH_JWKS_URI=http://keycloak:8080/realms/fincore-dev/protocol/openid-connect/certs \
docker compose --profile identity up -d --force-recreate core notification

TOKEN=$(curl -s -X POST \
  http://localhost:8180/realms/fincore-dev/protocol/openid-connect/token \
  -d grant_type=password -d client_id=fincore-cli \
  -d username=ada -d password=password | jq -r .access_token)

curl -H "Authorization: Bearer $TOKEN" http://localhost:58081/v1/products
```

Or press **Authorize** in Swagger UI and paste the token. The sign-in page
itself — the one a mobile app opens in an in-app browser tab — is at
<http://localhost:8180/realms/fincore-dev/account>.

Every credential in that realm is fake and published on purpose:
[`keycloak/README.md`](keycloak/README.md) explains the users, what each may do,
and what this deliberately does not cover — the ledger still takes a tenant
header, and no service-to-service mTLS exists yet.

**Two things announce themselves loudly at startup, and both are meant to.**
Services run with a development identity — headers, not tokens — until a
Keycloak realm is provisioned (ADR 0010); and Notification's senders deliver
nowhere, because the messaging connector that would make them real is not built.
A component that silently does its job badly is worse than one that fails.

## Building

```bash
./mvnw verify        # build everything, run every test
```

Requires Java 25 and a running PostgreSQL — `docker compose up -d postgres`.

**The suites run against `*_test` databases, never the ones the stack uses.** A
running deployable is a concurrent writer, and it took two mystifying failures
to establish that no amount of waiting inside a test makes one go away.
`db/init/` creates every database and role, but only on an empty volume; if your
volume predates that, add them in place:

```bash
docker compose exec postgres psql -U fincore -d postgres \
  -c "CREATE DATABASE ledger_test OWNER fincore;" \
  -c "CREATE DATABASE core_test OWNER fincore;" \
  -c "CREATE DATABASE notification_test OWNER fincore;"
```

Each service's README carries the detail — the roles, the deliberate
exceptions, and what its own suite covers.

## License

[AGPL-3.0-only](LICENSE) for the platform. SDKs and client libraries (when they
exist) will be Apache-2.0. Contributions require a signed [CLA](.github/CLA.md) — see
[CONTRIBUTING.md](.github/CONTRIBUTING.md).
