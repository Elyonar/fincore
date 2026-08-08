# Notification Service

> **The messenger.** It consumes domain events and turns them into messages a
> tenant's customer receives. It writes no money, calls no money-path API, and
> publishes no business facts. Its hardest problem is not sending — it is
> knowing exactly once that a message is owed, and being able to say why one
> was not sent.

**Status: design AGREED v1.5.1 — implemented, pre-1.0.** The schema, the intake
pipeline, the send worker, the Kafka listener and every endpoint `api.md`
documents exist, with **57 tests green against real PostgreSQL**. It is not
production-ready: nothing is delivered to a customer until the messaging
connector exists, and [`docs/testing.md`](docs/testing.md) carries the honest
list of what is still PARTIAL. Read [`docs/design.md`](docs/design.md),
then [`docs/CHANGELOG.md`](docs/CHANGELOG.md) before assuming anything about
state — and [`docs/testing.md`](docs/testing.md) for which suites actually run.
Code that contradicts the agreed design is a bug even if it works.

**Why it exists now, ahead of its phase:** PRD §9 places Notification in Phase 3
and Phase 0 excludes it by name. It is built early as the platform's **first
event consumer** — nothing had ever consumed an event, and ADR 0008's envelope
had already diverged between the two publishers without anything noticing.
Recorded in [ADR 0011](../../docs/adr/0011-first-consumer-before-phase-three.md).

---

## Memory map

| You want to know… | Read | Status |
|---|---|---|
| The design at a glance + the decision log | [`docs/design.md`](docs/design.md) | AGREED v1.5.1 |
| Boundaries, traffic, the intake pipeline, DR posture | [`docs/architecture.md`](docs/architecture.md) | AGREED v1.5.1 |
| The eight tables, ownership rules, decided edge cases | [`docs/data-model.md`](docs/data-model.md) | AGREED v1.5.1 |
| Endpoint surface, error catalog, suppression reasons | [`docs/api.md`](docs/api.md) | AGREED v1.5.1 — **built**, `ApiTest` proves the document and the surface agree |
| The nine invariants and every test suite | [`docs/testing.md`](docs/testing.md) | AGREED v1.5.1 |
| Every amendment since the design was agreed | [`docs/CHANGELOG.md`](docs/CHANGELOG.md) | v1.5.1 |
| Why this is built before Phase 3 | [ADR 0011](../../docs/adr/0011-first-consumer-before-phase-three.md) | Accepted |
| The event contract this service consumes | [ADR 0008](../../docs/adr/0008-event-contract.md) | Accepted |
| Why the backbone retains history | [ADR 0005](../../docs/adr/0005-kafka-event-backbone.md) | Accepted |
| Platform hard rules | [`AGENTS.md`](../../AGENTS.md) | Standing |
| What every service must have before it ships | [`service-scaffold.md`](../../docs/conventions/service-scaffold.md) | Standing |

## Quick facts

| | |
|---|---|
| Language / framework | Java 25 LTS, Spring Boot |
| Storage | PostgreSQL (own database, one schema, eight tables + Flyway's history) |
| Database roles | `notification_app` for traffic, `notification_worker` for the queue. Migrations run as the owner |
| Money representation | none — this service handles no money |
| Calls out to | Core (contact and consent lookup), the messaging connector (not built) |
| Events | consumes Core's business events; **publishes none in v1** |
| Channels | SMS and email, as rows in a channel registry — see below |
| Gateway credentials | **never held here** — they live in the messaging connector (PRD §4.6) |

## Run

```bash
# whole stack in Docker — database, broker, Core, and this service
docker compose up --build
curl http://localhost:58082/actuator/health/readiness  # {"status":"UP"}
open  http://localhost:58082/docs                      # interactive API

# infrastructure only, running the service from an IDE
docker compose up -d postgres kafka core
./mvnw -pl services/notification spring-boot:run
```

Published on host port **58082**, not 8082, for the same reason the ledger uses
58080 and Core 58081: this service's own tests bind 8082, and a container
holding it would mean `docker compose up` and `./mvnw verify` could not run at
the same time.

**Two banners at startup, and both are the point.** The senders announce that
they deliver nowhere — the messaging connector is not built — and the address
cipher announces that it is using a development key. A service that silently
does its job badly is worse than one that fails.

**The suite runs against `notification_test`, not `notification`.** A running
deployable is a concurrent writer: this service's own send worker, in a
container, drains the very queue a test is asserting about. `db/init/` creates
both databases and the two roles, but only on an empty volume — if yours
predates that:

```bash
docker compose exec postgres psql -U fincore -d postgres \
  -c "CREATE DATABASE notification_test OWNER fincore;"
```

**Two database roles, deliberately.** `notification_app` serves requests and the
intake, always scoped to one tenant; `notification_worker` claims the queue
across every tenant and therefore opts into a narrow worker policy rather than
holding `BYPASSRLS`, which would exempt it from row-level security on every
table at once. Migrations run as the owner; traffic never does.

## Adding a channel

A channel is a **row**, not an enum. It carries the only four things that vary:
address kind, required template parts, content model, cost unit.

| Adding… | Registry row | Sender class | Customer change | Migration |
|---|---|---|---|---|
| WhatsApp | yes | one | none — reuses `PHONE` | **none** |
| Push (PRD §4.9) | yes | one | a device-token source | **none** |
| Anything on an existing address kind | yes | one | none | **none** |

Nothing in the consumer, the dedupe, the policy engine, the suppression
catalogue or the send queue is channel-aware — they read the descriptor. The
startup check refuses to run with an enabled channel that has no sender, because
that channel would queue messages forever while the service reported itself
healthy.

Note that **WhatsApp is not in the PRD**, which names SMS, email and push.
Making it cheap is design; choosing to support it is a product decision and a
PRD bump.

## The three things most likely to be got wrong

1. **Two events, one business moment.** The ledger publishes
   `posting.completed` and Core publishes `transfer.completed` about the same
   transfer. Subscribing to both sends two messages, and dedupe on
   `(publisher, eventId)` will not save you — it stops redelivery of one event,
   not two different events. One notification category has exactly one
   publisher.
2. **Replaying history.** Kafka retains for Compliance's benefit; notifications
   care only about now. A consumer started from earliest sends weeks of stale
   alerts. Start at latest *and* drop events older than the configured age.
3. **Silent drops.** Every consumed event ends as a message or as a suppression
   with a reason code. "Why didn't my customer get an SMS?" must be answerable
   from the database.

## Known limitations

Real, and listed where a reader will find them rather than left to be
discovered.

| Area | State |
|---|---|
| Delivery | **Nothing reaches a customer.** Both senders are the `log` adapter; they render, mark sent, and deliver nowhere. The banner at startup says so. The messaging connector is what makes them real, and connectors come last by decision |
| Delivery receipts, bounces, monetary cost | Need a gateway to report them. The status model exists and is unexercised; segments are counted, money per message is not |
| Error-catalog test | **Missing.** `api.md`'s codes and the `Suppressed` reasons can drift from the enums without the build noticing |
| Metrics | Queue depth, oldest-pending age and exhausted-attempts count export at `/actuator/prometheus` (v1.5). Money-per-message still waits on a gateway that can report it |
| Startup summary | Two loud banners — senders that deliver nothing, and the development address key — but not the consolidated summary the ledger prints |
| Locale | Selected from the customer, then the tenant default. No per-customer locale exists in Customer for most records yet, so in practice almost everything falls back |
| Push, WhatsApp | Registry rows and a sender class away, and neither is built. Push is in the PRD; WhatsApp is not |
| Performance | No throughput target agreed and nothing measured |

## Not in this deployable

Money movement of any kind. Gateway credentials or sender ids. Customer profile
data or consent records — both belong to Customer. OTP delivery: that path goes
from Keycloak's SPI straight to the messaging connector (PRD §4.10), bypassing
notification policy deliberately, because OTP wants the opposite retry rule.
