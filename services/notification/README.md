# Notification Service

> **The messenger.** It consumes domain events and turns them into messages a
> tenant's customer receives. It writes no money, calls no money-path API, and
> publishes no business facts. Its hardest problem is not sending — it is
> knowing exactly once that a message is owed, and being able to say why one
> was not sent.

**Status: design AGREED v1.2 — implemented, pre-1.0.** The schema, the intake
pipeline, the send worker, the Kafka listener and every endpoint `api.md`
documents exist, with **46 tests green against real PostgreSQL**. It is not
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
| The design at a glance + the decision log | [`docs/design.md`](docs/design.md) | AGREED v1.1 |
| Boundaries, traffic, the intake pipeline, DR posture | [`docs/architecture.md`](docs/architecture.md) | AGREED v1.1 |
| The seven tables, ownership rules, decided edge cases | [`docs/data-model.md`](docs/data-model.md) | AGREED v1.1 |
| Endpoint surface, error catalog, suppression reasons | [`docs/api.md`](docs/api.md) | AGREED v1.1 — **none built yet** |
| The nine invariants and every test suite | [`docs/testing.md`](docs/testing.md) | AGREED v1.1 |
| Every amendment since the design was agreed | [`docs/CHANGELOG.md`](docs/CHANGELOG.md) | v1.2 |
| Why this is built before Phase 3 | [ADR 0011](../../docs/adr/0011-first-consumer-before-phase-three.md) | Accepted |
| The event contract this service consumes | [ADR 0008](../../docs/adr/0008-event-contract.md) | Accepted |
| Why the backbone retains history | [ADR 0005](../../docs/adr/0005-kafka-event-backbone.md) | Accepted |
| Platform hard rules | [`AGENTS.md`](../../AGENTS.md) | Standing |
| What every service must have before it ships | [`service-scaffold.md`](../../docs/conventions/service-scaffold.md) | Standing |

## Quick facts

| | |
|---|---|
| Language / framework | Java 25 LTS, Spring Boot |
| Storage | PostgreSQL (own database, one schema, six tables + Flyway's history) |
| Database roles | `notification_app` for traffic, `notification_worker` for the queue. Migrations run as the owner |
| Money representation | none — this service handles no money |
| Calls out to | Core (contact and consent lookup), the messaging connector (not built) |
| Events | consumes Core's business events; **publishes none in v1** |
| Channels | SMS and email, as rows in a channel registry — see below |
| Gateway credentials | **never held here** — they live in the messaging connector (PRD §4.6) |

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

## Not in this deployable

Money movement of any kind. Gateway credentials or sender ids. Customer profile
data or consent records — both belong to Customer. OTP delivery: that path goes
from Keycloak's SPI straight to the messaging connector (PRD §4.10), bypassing
notification policy deliberately, because OTP wants the opposite retry rule.
