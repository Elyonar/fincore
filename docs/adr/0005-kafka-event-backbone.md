# ADR 0005 — Broker-agnostic event backbone, Kafka recommended

**Status:** Accepted · 2026-08-05
**Supersedes:** nothing

## Context

The constitution commits the platform to an event-driven backbone: services
publish domain events, consumers subscribe. The ledger already writes events
transactionally to an outbox and relays them, but the relay has been handing
them to a logging adapter — so no event has ever left the service.

Deferring the choice cost nothing while nothing consumed events. It now costs
something concrete: "events are emitted" is a claim the platform makes and
cannot currently demonstrate, and every service built after the ledger would
inherit the same unproven integration.

The consumers named in the PRD are not symmetric in what they need:

- **Compliance and regulatory reporting** must be able to rebuild state from
  history — a report signed off last quarter has to be reproducible, and a
  consumer added next year needs the events that predate it.
- **Notifications** care only about what is happening now.
- **Reporting and AI** sit closer to Compliance: they replay.

That asymmetry decides this. A queue that discards a message once acknowledged
serves notifications well and serves Compliance badly, and Compliance is the
harder constraint.

## Decision

**The backbone is a deployment choice, not a code dependency.** The ledger
publishes through the `EventPublisher` seam and `ledger.events.broker` selects
an adapter at runtime: `kafka`, `rabbit`, or `log`. No broker type reaches the
domain, and changing broker requires no rebuild.

**Kafka is the recommended default**, in KRaft mode, and what `compose.yaml`
starts. A broker outage delays delivery; it never fails a posting, because the
outbox already decoupled the two.

Recommending rather than mandating matters: the argument for Kafka is about the
*platform's* consumers, not about the ledger. An operator whose consumers only
react to what is happening now should not be made to run Kafka to satisfy an
argument that does not apply to them.

### Why Kafka

- **Retention is replay.** A topic is a durable log, not a queue drained by its
  first reader. Compliance can rebuild from the beginning; a service written in
  2027 can consume 2026's events. With RabbitMQ that history would have to be
  rebuilt from the ledger's own tables through a bespoke backfill, per consumer.
- **At-least-once plus consumer-side dedupe is already the contract.** The relay
  guarantees at-least-once and `architecture.md` requires consumers to
  deduplicate on outbox id. That is Kafka's model, not something bolted on.
- **Per-key ordering matches the aggregate.** Keying on `aggregate_id` gives
  ordered delivery per account or transaction without pretending to a global
  order the relay explicitly does not provide.
- **KRaft removes ZooKeeper**, so a development broker is one container rather
  than a cluster to babysit.

### RabbitMQ — supported, not recommended for this platform

Operationally lighter and genuinely simpler, and if notifications were the only
consumer it would win. But a message acknowledged is a message gone. Rebuilding
regulatory history would mean a bespoke backfill per consumer, reading the
ledger's tables directly — which is exactly the coupling the event backbone
exists to avoid.

It ships as a supported adapter (`ledger.events.broker=rabbit`,
`docker compose --profile rabbit up`) because that trade-off is a deployment's
to make. Two things it requires, both learned the hard way here:

- **The exchange must be declared.** RabbitMQ silently discards a message sent
  to an exchange that does not exist — the publish returns normally and the
  relay marks the row published. `RabbitTopology` declares it durably.
- **Publisher confirms are mandatory.** Without them "sent" means the client
  wrote to a socket, not that the broker took it.

### Redis — not suitable as the backbone

Redis Pub/Sub has no persistence and no delivery guarantee: a consumer that is
down misses the message permanently. That disqualifies it outright for money
events.

Redis Streams is a closer fit — it has persistence, consumer groups and
replay — and would work for notifications. It is still the wrong choice for the
*backbone*, because retention is bounded by memory and durability rests on
RDB/AOF snapshots. The requirement that drove this decision is Compliance
replaying years of history; a memory-bound log cannot promise that, and a
backbone that quietly drops the oldest entries is worse than one that never
claimed to keep them.

Redis remains a good fit elsewhere in the platform — caching, rate limiting,
idempotency scratch space — and adding a Streams adapter later is a one-class
change if a concrete consumer ever justifies it.

### Why not stay on PostgreSQL

Defensible while the ledger is the only service, and it was the right call until
now. It stops being right the moment a second service consumes: every consumer
would need credentials to the ledger's database, breaking database-per-service —
the constitution's fourth rule.

## Consequences

- One more piece of infrastructure to run, monitor and secure. That is the real
  cost of this decision and it is accepted, not waved away.
- Local development gains a Kafka container in `compose.yaml`, alongside
  PostgreSQL.
- The ledger keeps no Kafka types in its domain: publishing stays behind
  `EventPublisher`, and the logging adapter remains the default so tests and a
  bare `./mvnw verify` need no broker.
- Topic naming, partitioning and retention are operational concerns recorded
  with the deployment, not in the ledger's design.
- Consumers must deduplicate on outbox id. Kafka gives at-least-once; the
  ledger's contract already assumed it.

## Revisiting

If Compliance and Reporting turn out never to replay — if every consumer only
ever reacts to what is happening now — Kafka is heavier than this platform
needs, and RabbitMQ or PostgreSQL-native delivery would be the honest
correction. That would be a new ADR superseding this one, not an edit here.
