# ADR 0008 — One event envelope for the whole platform

**Status:** Accepted · 2026-08-05
**Supersedes:** nothing. Completes [ADR 0005](0005-kafka-event-backbone.md).

## Context

[ADR 0005](0005-kafka-event-backbone.md) chose the backbone and left the
contract open: it settled *where* events go, not what they look like. That was
sufficient while the ledger was the only publisher, because a single publisher
cannot disagree with itself.

Core changes that. It is the platform's second publisher and its first consumer,
and the services after it — Compliance, Notification, Reporting, AI — are
consumers of everything. If the envelope is settled per service, two envelopes
exist within a month and every future consumer inherits both, permanently.

The properties the backbone already commits to (ADR 0005, and the ledger's
outbox contract) constrain the answer: delivery is at-least-once, ordering is
per-aggregate and never global, and consumers must be state-based rather than
sequence-based.

## Decision

**Every domain event on the platform carries the same envelope, and payloads are
thin.**

### The envelope

| Field | Meaning |
|---|---|
| `eventId` | The publishing service's outbox row id. **The deduplication key.** Unique per publisher, monotonic per publisher, stable across redeliveries |
| `eventType` | Dotted domain name, e.g. `posting.completed`, `transfer.failed` |
| `aggregateId` | The entity the event is about. The partition/routing key — this is what makes per-aggregate ordering real |
| `tenantId` | Always present. A consumer must never have to infer tenancy from the payload |
| `occurredAt` | When the state change committed, not when it was relayed |
| `epoch` | The publisher's restore epoch. A consumer discards events from an epoch newer than the one it has been told to trust |
| `payload` | Thin: identifiers plus the minimum summary a consumer needs to decide whether to care |

### The rules

- **Consumers deduplicate on `(publisher, eventId)`.** At-least-once is the
  contract; exactly-once is not on offer and pretending otherwise produces
  consumers that quietly lose events when they are wrong about it.
- **Consumers are state-based.** React to an event by fetching current state
  through the publisher's read API. Never reconstruct state by replaying a
  sequence — ordering is per aggregate only, and events are summaries, not a
  change log.
- **Payloads are thin, and never database-shaped.** A payload is a published
  contract; a table is an implementation detail. Publishing rows means every
  schema change is a breaking change for consumers, and it puts PII on the bus
  for no benefit.
- **Money in payloads is a decimal string,** matching the rule every read API
  follows. Balances and sums are unbounded and exceed exact JSON number range; a
  single rule everywhere means a consumer parsing events and a consumer parsing
  responses never disagree.
- **Events are additive.** New optional fields are a minor change. Removing a
  field, renaming one, or changing a meaning requires a new `eventType` — the
  old one keeps flowing until consumers have moved.
- **Topic naming: `fincore.<publisher>.<eventType>`** — e.g.
  `fincore.ledger.posting.completed`, `fincore.core.transfer.completed`.
- **Every publisher writes to a transactional outbox** in the same database
  transaction as the state change, with a relay that polls
  `FOR UPDATE SKIP LOCKED` on unpublished rows ordered by id. **Never a
  watermark** ("id > last seen"): sequence values are assigned at insert, not at
  commit, so a slow writer can commit a low id after a higher one was already
  relayed, and a watermark skips it forever, silently.
- **One fact has one publisher.** A domain event is emitted by the service that
  owns the state change. Where two services observe the same business moment,
  they publish *different* events about it — the ledger publishes
  `posting.completed`, Core publishes `transfer.completed` — and never restate
  each other's facts. Two publishers of one fact means consumers eventually
  disagree about which is authoritative.

## Rationale

The envelope is small because everything in it is load-bearing. `eventId` makes
at-least-once survivable. `aggregateId` is what makes per-key ordering a real
guarantee instead of a hope. `tenantId` prevents every consumer from writing its
own tenancy-extraction bug. `epoch` is what allows a restored publisher to be
distrusted for the window it was rewound past — without it, a consumer applies
events describing states the restored publisher now denies.

Thin payloads are the decision that pays repeatedly: less PII on the bus, and
they *force* the state-based consumer pattern that the ordering guarantee
already requires. A fat payload invites exactly the sequence-based consumer the
contract cannot support.

## Consequences

- Publishing a new event type is a contract change, recorded in the publishing
  service's CHANGELOG.
- The outbox pattern, the relay contract and the staleness alert are part of the
  service scaffold ([`../conventions/service-scaffold.md`](../conventions/service-scaffold.md)),
  not per-service inventions.
- Consumers need durable dedupe storage keyed on `(publisher, eventId)`. Core is
  the first to need it; if a second consumer follows, it becomes a `libs/`
  candidate.
- A published event carrying money must be reviewed for the decimal-string rule,
  because the failure mode is silent precision loss in a consumer nobody owns.

## Revisiting

If a consumer emerges that genuinely requires a total order across aggregates,
this contract cannot serve it and the honest answer is a new ADR — not a
per-service exception, and not a consumer quietly assuming an ordering the relay
never promised.
