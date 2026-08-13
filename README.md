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
| Read a service's design | its own `README.md` and `docs/` — [ledger](services/ledger/README.md), [core](services/core/README.md), [customer](services/customer/README.md), [product](services/product/README.md), [identity](services/identity/README.md), [notification](services/notification/README.md) |
| Run the platform locally | [Running it](#running-it), below |
| Contribute code or docs | [`.github/CONTRIBUTING.md`](.github/CONTRIBUTING.md) + [`.github/CLA.md`](.github/CLA.md) |
| Report a security issue | [`.github/SECURITY.md`](.github/SECURITY.md) |
| Work on this repo with an AI agent | [`AGENTS.md`](AGENTS.md) — the canonical memory map |

### Services

| Service | What it does | Status | Docs |
|---|---|---|---|
| **Ledger** | Double-entry posting engine — the single source of monetary truth. Accounts, entries, balances, holds. | ✅ Design AGREED v1.11 · implemented (pre-1.0) | [README](services/ledger/README.md) · [design](services/ledger/docs/design.md) · [data model](services/ledger/docs/data-model.md) · [architecture](services/ledger/docs/architecture.md) · [API](services/ledger/docs/api.md) · [posting algorithm](services/ledger/docs/posting-algorithm.md) · [testing](services/ledger/docs/testing.md) |
| **Core** | One deployable, four modules — `organization`, `orchestration`, `admin` (stateless; proxies the identity service) and `app`. Owns the money path: sagas, fee application, limits, tills and the organizational tree (ADR 0012). The only caller of the Ledger's write API, and the composition point for anything needing more than one service at once. | ✅ Design AGREED v2.4 · implemented (pre-1.0) | [README](services/core/README.md) · [design](services/core/docs/design.md) · [outcome protocol](services/core/docs/outcome-protocol.md) · [saga protocol](services/core/docs/saga-protocol.md) · [data model](services/core/docs/data-model.md) · [API](services/core/docs/api.md) · [testing](services/core/docs/testing.md) |
| **Customer** | The people this institution banks: identity, KYC tier, contact and consent, numbering, and the link from a customer to an account they hold. Holds the platform's only PII. Was a Core module until ADR 0020. | ✅ Implemented (pre-1.0) | [README](services/customer/README.md) · [ADR 0020](docs/adr/0020-customer-and-product-become-deployables.md) |
| **Product** | The catalogue, its versions, and the pricing decision the money path asks for: what does this cost, and is it allowed. Published versions are immutable, enforced by trigger. Was a Core module until ADR 0020. | ✅ Implemented (pre-1.0) | [README](services/product/README.md) · [ADR 0020](docs/adr/0020-customer-and-product-become-deployables.md) |
| **Identity** | First-party identity (ADR 0018), which retired Keycloak (ADR 0010). Issues the tokens every other service verifies through [`libs/auth`](libs/auth/README.md), holds the staff directory, and mints the tenant-scoped service principals a service needs to read another's data (ADR 0019). | Design DRAFT v0.1 · implemented and running | [README](services/identity/README.md) · [ADR 0018](docs/adr/0018-first-party-identity-service.md) |
| **Notification** | The platform's first event consumer. Turns Core's business events into messages over a registry of channels; writes no money and holds no gateway credentials. | ✅ Design AGREED v1.7 · implemented (pre-1.0) | [README](services/notification/README.md) · [design](services/notification/docs/design.md) · [architecture](services/notification/docs/architecture.md) · [data model](services/notification/docs/data-model.md) · [API](services/notification/docs/api.md) · [testing](services/notification/docs/testing.md) |
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
| [0013](docs/adr/0013-lending-module-first.md) | Lending starts as a Core module, above Orchestration; extraction triggers named — **withdrawn**: lending is out of scope for this build |
| [0014](docs/adr/0014-ui-runway.md) | The edge is configuration, reads come through Core, identity gets real first |
| [0015](docs/adr/0015-control-plane-and-tenant-provisioning.md) | The control plane is a deployable of its own, and provisioning is a saga — **Deferred** |
| [0016](docs/adr/0016-tenant-bootstrap-manifest.md) | Tenants are declared in a manifest and seeded at startup |
| [0017](docs/adr/0017-tenant-defined-roles.md) | Permissions are platform vocabulary; roles are tenant-composed |
| [0018](docs/adr/0018-first-party-identity-service.md) | Identity is a first-party service; Keycloak is retired |
| [0019](docs/adr/0019-tenant-scoped-service-principals.md) | A service that reads tenant data holds a tenant-scoped service principal |
| [0020](docs/adr/0020-customer-and-product-become-deployables.md) | Customer and Product become deployables of their own — **supersedes 0006** |

## Repository layout

```
fincore/
├── services/          # independently deployable Spring Boot services
│   ├── ledger/        # the first deployable — double-entry posting engine
│   │   ├── README.md  # the service's own map: purpose, boundaries, doc index
│   │   └── docs/      # the service's design & deep-dive docs
│   ├── core/          # one deployable, four modules + assembly:
│   │                  #   organization · orchestration · admin · app
│   ├── customer/      # the people this institution banks — the only PII
│   ├── product/       # the catalogue and the pricing decision
│   ├── identity/      # tokens, the staff directory, service principals
│   └── notification/  # the first event consumer — messages, not money
├── libs/              # shared internal libraries (auth, events) — arrive when needed
├── docs/
│   ├── prd.md         # product requirements
│   ├── adr/           # Architecture Decision Records (cross-cutting only)
│   ├── conventions/   # commits, design amendments, the service scaffold
│   └── README.md      # documentation conventions
├── db/init/           # local-only: one database and role per deployable
├── bootstrap/         # the tenant manifest and its seeding (ADR 0016)
├── edge/              # nginx: the one origin a browser talks to (ADR 0014)
├── scripts/           # provisioning and restore drills
├── .github/           # CONTRIBUTING, CLA, SECURITY, CI workflows
├── AGENTS.md          # memory map for AI agents & new contributors
├── pom.xml            # Maven multi-module root (Java 25 LTS)
└── compose.yaml       # local dev stack: PostgreSQL, Kafka, RabbitMQ, the edge,
                       #   and all five deployables
```

A monorepo is not a monolith: each directory under `services/` builds its own
container and owns its own database. Where a deployable holds several modules,
each module owns a schema and its own database role and reaches the others only
through their published interfaces — never their tables (PRD §3.4,
[ADR 0006](docs/adr/0006-modular-core.md), amended by
[ADR 0020](docs/adr/0020-customer-and-product-become-deployables.md)). Every service documents itself (`README.md` + `docs/`
inside the module); the root `docs/` holds only cross-cutting material. The
service README is the navigator for its own docs — this README links to the
service, not past it.

### The databases

Six, on one PostgreSQL instance locally. Each is owned end to end by one
deployable — which also owns its migrations — and `db/init/` creates them all on
an empty volume, alongside a `*_test` database per service so `docker compose up`
and `./mvnw verify` never contend for the same rows.

| Database | Owned by | Holds |
|---|---|---|
| `ledger` | Ledger | Accounts, entries, holds, periods, invariants, and the currency registry every amount is denominated in |
| `core` | Core | Sagas, tills, internal accounts, organizational units, approvals — one schema and one role per module |
| `customer` | Customer | People, KYC tiers, contact and consent, numbering, the accounts they hold. **The platform's only PII** |
| `product` | Product | The catalogue, its versions, and the fee and limit rules that price the money path |
| `notification` | Notification | Templates, policy, delivery queue, suppressions |
| `identity` | Identity | Staff, credentials, roles, service clients, the signing key's metadata |

Never share one across deployables and never read another's tables — that is the
rule a monorepo makes easy to break and this layout makes obvious
(PRD §3.4, [ADR 0007](docs/adr/0007-tenant-isolation-pattern.md)).

Within a database, **traffic connects as a restricted role, migrations as the
owner.** A deployable holding several modules gets one role per module, granted
on its own schema and nothing else, so a cross-module query fails at runtime and
in the suite rather than surviving until somebody tries to extract a module.
None of these roles may be `SUPERUSER` or `BYPASSRLS`: PostgreSQL skips
row-level security entirely for either, which would leave every tenant policy
inert while the catalog still reported it enabled.

There is no `keycloak` database any more. Identity is first-party
([ADR 0018](docs/adr/0018-first-party-identity-service.md)), so the tables that
used to be a vendor's are ours, migrated by our Flyway, in a database named for
the deployable that owns it like every other.

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
docker compose up --build          # PostgreSQL, Kafka, the edge, and all five deployables
```

> **Migration baseline reset (2026-08-11).** Core's per-module migrations were
> squashed into a single `V1__baseline.sql` per schema when the lending module
> was withdrawn. There is deliberately no in-place upgrade path: a database that
> applied the old V1..V9 chains fails Flyway validation at startup. Wipe the
> volume and let the stack rebuild —
> `docker compose down -v && docker compose up --build`. No environment holding
> data of record existed at the time of the squash; from here on, schema changes
> are incremental migrations again.

| | Health | Interactive API |
|---|---|---|
| Edge | http://localhost:8088/ | — (it routes; it serves nothing of its own) |
| Ledger | http://localhost:58080/actuator/health/readiness | http://localhost:58080/docs |
| Core | http://localhost:58081/actuator/health/readiness | http://localhost:58081/docs |
| Notification | http://localhost:58082/actuator/health/readiness | http://localhost:58082/docs |
| Identity | http://localhost:58183/actuator/health/readiness | http://localhost:58183/docs |
| Customer · Product | not published — reached through the edge at `/api/customer/`, `/api/product/` | |

Host ports are deliberately not the service's own: each service's test suite
binds its real port, and a container holding it would mean `docker compose up`
and `./mvnw verify` could not run at the same time. A stack should never be able
to break the build, or the other way round. Inside the compose network the ports
are unchanged — ledger 8080, core 8081, notification 8082, identity 8083,
product 8084, customer 8085.

Customer and Product publish no host port on purpose. Nothing outside the
platform calls them directly: a browser reaches every service through the one
origin the edge serves (ADR 0014), and a published port would be a second way in
that no configuration describes.

RabbitMQ is available as an alternative backbone
(`FINCORE_EVENTS_BROKER=rabbit`).

### Logging in

Tenants come from [`bootstrap/tenants.json`](bootstrap/tenants.json) — one
super-administrator each, registered with every deployable by
`bootstrap/seed-registries.sh`
([`tenant-bootstrap.md`](docs/conventions/tenant-bootstrap.md), ADR 0016).

```bash
bootstrap/seed-registries.sh              # once the services are up

TOKEN=$(curl -s -X POST http://localhost:8088/api/identity/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"ada.admin","password":"<the temporary password>"}' | jq -r .accessToken)

curl -H "Authorization: Bearer $TOKEN" http://localhost:8088/api/product/v1/products
```

`seed-registries.sh` is not optional: a tenant absent from the five registries
authenticates perfectly and then gets a bodiless 404 from every endpoint.

The seeded administrator must change the temporary password and give a contact
number before they can work. From there the portal's setup flow walks the
institution through branches, staff, internal accounts, a product, its pricing,
publishing it, and a till — everything else is created by the institution
itself, which is the whole point of seeding one administrator and stopping.

Or press **Authorize** in Swagger UI and paste the token.

**Two things announce themselves loudly at startup, and both are meant to.**
Identity mints an ephemeral signing key unless
`fincore.identity.signing.private-key-pem` is set — fine on a laptop, fatal in a
deployment, where every token it ever issued becomes invalid on restart. And
Notification's senders deliver nowhere, because the messaging connector that
would make them real is not built. A component that silently does its job badly
is worse than one that fails.

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
