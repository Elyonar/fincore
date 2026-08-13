# Notification — Design & Decision Log

**Status:** AGREED v1.7 (2026-08-11) — implemented from here; amendments via
[`CHANGELOG.md`](CHANGELOG.md) and the
[design-change convention](../../../docs/conventions/design-changes.md). Code
that contradicts this document is a bug even if it works.

**Source:** PRD §4.9 (Notification), §4.6 #3 (SMS gateway connector), §4.10
(OTP via the gateway connector), §5 (communication map), §7 (NFRs, NDPR), §8
(testing); [ADR 0005](../../../docs/adr/0005-kafka-event-backbone.md) (backbone),
[ADR 0007](../../../docs/adr/0007-tenant-isolation-pattern.md) (tenancy),
[ADR 0008](../../../docs/adr/0008-event-contract.md) (event contract),
[ADR 0009](../../../docs/adr/0009-service-to-service-identity.md) (identity),
[ADR 0011](../../../docs/adr/0011-first-consumer-before-phase-three.md)
(why this is built now).

---

## The design, by topic

| Topic | Doc |
|---|---|
| Boundaries, traffic, the intake pipeline, concurrency, DR posture | [`architecture.md`](architecture.md) |
| The eight tables, ownership rules, decided edge cases | [`data-model.md`](data-model.md) |
| Endpoint surface, error catalog, suppression reasons | [`api.md`](api.md) |
| The nine invariants and every test suite, with status markers | [`testing.md`](testing.md) |
| Amendments since this design was agreed | [`CHANGELOG.md`](CHANGELOG.md) |

This design is **AGREED**. Changes to it are amendments recorded in
[`CHANGELOG.md`](CHANGELOG.md), in their own PR, ahead of the code — never
silent edits. Code that contradicts it is a bug even if it works.

## One paragraph

Notification consumes domain events and turns them into messages a tenant's
customer receives, over SMS and email. It writes no money, calls no money-path
API, and publishes no business facts. It owns templates, delivery policy
(categories, quiet hours, channel selection), the send queue, and the delivery
record. It holds **no gateway credentials** — those live in the messaging
connector, per PRD §4.6 — no customer profile, and no consent records; contact
details and consent belong to Customer.

It is the platform's **first event consumer**, so consumer-side deduplication,
epoch fencing, ordering tolerance and replay safety are built here first and
extracted to `libs/events` when a second consumer arrives.

## v1 scope

**In:** SMS and email as the first two entries in a channel registry (D-13);
template management per tenant, channel and locale, with versions; category-aware delivery policy; consumption of Core's business events;
the send queue with append-only attempt history; suppression records; the
delivery-status model; message and segment counting for cost attribution; an
admin API for templates and a query API for delivery state.

**Out, deliberately:**

- **Real delivery.** The messaging connector does not exist (connectors are
  last, by decision). v1 ships `log` adapters that deliver nothing and say so
  loudly at startup, exactly as the ledger's `log` broker adapter does.
- **Delivery receipts (DLR) and monetary cost.** Both come from a gateway.
  v1 counts messages and SMS segments; money per message arrives with the
  connector.
- Push and WhatsApp. Push is in the PRD (§4.9) and WhatsApp is not in it at
  all; neither is built, because no mobile app exists to receive a push and no
  messaging connector exists to carry either. Both are a registry row and a
  sender class when they are wanted — D-13 states what that costs.
- Publishing events (D-2).
- Analytics beyond operational queries — that belongs to Compliance & Reporting.

---

## Decision log

Format: decision · resolution · rationale.

**D-1 · Packaging → its own deployable, its own database.** ADR 0006 merged
Customer/Product/Orchestration because the decision path was synchronous and
non-optional. Notification is the opposite on both counts: nothing waits for it,
and a failed send does not fail a transfer. The ADR's own reasoning —
separation isolates faults when a dependency is asynchronous or optional —
points here.

**D-2 · It publishes nothing in v1.** PRD §4.9 names no events, and a
notification is not a domain fact anyone's state depends on. Publishing would
cost an outbox table, a relay and a retention job to serve no consumer. When
Compliance exists and needs "was the customer told",
`notification.sent` / `notification.failed` arrive then, as a contract change in
this service's CHANGELOG.

**D-3 · One notification category has exactly one publisher — the double-send is
prevented structurally, not deduped away.** The ledger publishes
`posting.completed`; Core publishes `transfer.completed`. ADR 0008 is explicit
that these are different events about one business moment and that neither
restates the other. A consumer subscribed to both sends two messages for one
transfer, and **dedupe on `(publisher, eventId)` does not help** — that key stops
redelivery of one event, not two distinct events about one moment.

So customer-facing transaction alerts are driven by **Core's business events
only** — today `transfer.completed`, plus the reversal event once Core settles
its name (its `architecture.md` documents `transfer.reversed`; the code emits
`transfer.reversal_initiated`) — never the ledger's postings. In v1 this loses
nothing: Core is the ledger's only writer, so every posting originates in a Core
saga. The rule generalises: when rails and future modules publish, each category names
its one publisher, and a category with two candidate publishers is a design
error to be resolved before it ships.

**D-4 · Dedupe at two levels, because they fail differently.**
`(publisher, event_id)` unique — durable, the price of at-least-once. And
`(tenant_id, business_moment_key, category, channel, recipient_ref)` unique on
the notification itself — the backstop that survives a mistake in D-3, a
redriven topic, or a future second publisher. The first is protocol; the second
is policy.

**D-5 · Start at latest, and independently drop stale events.** ADR 0005 records
that notifications "care only about what is happening now" while Kafka retains
history for Compliance's benefit. A first deployment consuming from earliest
would send weeks of historical alerts. Two independent guards: the consumer
starts at latest, **and** an event whose `occurredAt` is older than
`max-event-age` (default 15 minutes) is suppressed with a recorded reason. Belt
and braces, because a consumer offset is an operational detail somebody
eventually resets, and the guard is what makes that survivable.

`occurredAt` is the outbox row's `created_at` — when the state change committed.
A relay timestamp would make every event look fresh after an outage, which is
precisely when this guard matters.

**D-6 · Epoch fencing per ADR 0008.** An event carrying an epoch newer than the
one this consumer is configured to trust is suppressed, alerted on, and never
applied. One field name across publishers (`epoch`), so a future consumer of
both reads them the same way.

**D-7 · Recipients are fetched, never mirrored.** ADR 0008 keeps PII off the bus
and requires state-based consumers, so Notification asks Customer who owns the
account at send time, with a short-TTL cache. It does **not** keep a customer
profile: Core's customer schema is documented as the only home for that, and a
second copy makes the claim false platform-wide.

*This needs a Core API that does not exist.* `api.md` offers
`GET /v1/customers/{id}` and no reverse lookup from an account. The lookup
returns, in one call, the customer id, the contact addresses for every channel,
and the per-category consent of D-20 — one round trip on the send path, and one
place where a customer's communication state is authoritative. It is a **Core
contract addition**, landing as a Core amendment before this service can send
anything.

**D-8 · The destination address is PII at rest, and treated as such.** A
delivery record must say where a message went, so the address is stored
encrypted, never written to a log in plaintext, and purged on a retention
schedule (default 90 days, tenant-configurable). PRD §7's NDPR obligations
attach to this table specifically.

**D-9 · Categories drive policy: `TRANSACTIONAL`, `SERVICE`, `MARKETING`.**
Quiet hours and opt-out apply per category. **Transactional alerts are exempt
from quiet hours and cannot be opted out of**: a debit alert is a fraud control,
and one held until 07:00 about a 02:00 debit has been converted from a control
into a receipt.

**D-10 · Quiet hours are per tenant, per category, in the tenant's timezone** —
the same business-date rule the ledger already applies, so two services never
disagree about what "today" means.

**D-11 · Templates are versioned and append-only, and a sent message records the
version that produced it.** The same shape as `product_versions`: `version`
orders history, `effective_from` decides which is live, a published version is
never edited. Without the recorded version, "what did we actually send that
customer in March" is unanswerable.

**D-12 · Strict variable binding. A missing variable suppresses the message.**
No message containing `null`, an empty span, or a raw placeholder is ever
delivered. Rendering is a total function or it does not run.

**D-13 · A channel is a registered descriptor, never an enum in the code.**
This is the decision that decides how much a fifth channel costs. Channels
differ along four axes and nothing else:

| Axis | SMS | Email | Push (PRD §4.9) | WhatsApp (not in the PRD) |
|---|---|---|---|---|
| Address kind | `PHONE` | `EMAIL` | `DEVICE_TOKEN` | `PHONE` |
| Required parts | `body` | `subject`, `body` | `title`, `body` | `body` |
| Content model | `SEGMENTED` (GSM-7 160 / UCS-2 70) | `PLAIN` | `PLAIN` | `PLAIN` |
| Cost unit | segment | message | message | message |

So the channel is a **row in a `channels` registry**, holding exactly those four
properties plus `enabled`. `notifications.channel`, `templates.channel` and
`channel_policy.channel` are foreign keys to it — deliberately *not* a `CHECK`
list, because a CHECK means a migration for every new channel, and that single
choice is the difference between "add a channel" and "change the schema".

**What adding a channel costs, stated so the claim can be checked:**

| Channel | Registry row | Sender | Customer change | Migration |
|---|---|---|---|---|
| WhatsApp | yes | one class | **none** — reuses `PHONE` | **none** |
| Push | yes | one class | a device-token source | **none** |
| Any channel on an existing address kind | yes | one class | none | none |

Nothing in the consumer, the dedupe, the policy engine, the suppression
catalogue or the send queue is channel-aware. They read the descriptor.

**The accepted cost, because "flexible" is never free:** template content is
`parts JSONB` rather than typed columns, so "email has a subject" is enforced by
the registry's `required_parts` at publish time and by a trigger, not by a
`NOT NULL`. A schema-enforcement test asserts a template missing a required part
is rejected — the guarantee moves, it does not weaken.

**Channel selection is tenant configuration per category**, as an ordered
fallback. A customer with no address for the selected channel falls through to
the next; a customer with no address for any configured channel is a suppression
with a reason, never a silent drop.

**D-14 · Content limits come from the descriptor, not from an `if`.** PRD §4.9
requires Hausa/Yoruba/Igbo templates in the same breath as per-tenant cost
reporting, and those two collide on SMS: diacritics force UCS-2, which drops a
segment from 160 characters to 70. A template whose rendered worst case exceeds
the configured cap is rejected at publish, not discovered on the bill.

The rule generalises rather than hardcoding SMS: a channel declares its content
model, and `SEGMENTED` is the only one that counts segments. A `PLAIN` channel
records one unit per message. Adding a channel with a different length model
means adding a content model — the one genuinely new concept a future channel
could bring, and the place it belongs.

**D-15 · Delivery goes through a `MessageSender` seam; each implementation
declares the channel it serves.** `log` is the default for every channel and
delivers nothing, announcing itself at startup per scaffold §10. Connector-backed
implementations arrive with the messaging connector, which holds per-tenant
sender ids and credentials — Notification never does (PRD §4.6).

**Startup fails if an enabled channel has no sender, or two.** Otherwise a
channel enabled in the registry with no adapter behind it queues messages that
can never leave, and the service reports itself healthy while a tenant's alerts
pile up invisibly — the same failure the `log` adapter's banner exists to
prevent, arriving through configuration instead of code.

**D-16 · OTP does not route through this service.** PRD §4.10's Keycloak SPI
calls the messaging connector directly. OTP wants low latency, no quiet hours,
no dedupe window, no tenant-authored template content, and — per D-19 — the
opposite retry policy. Routing it through notification policy would make every
one of those a bug waiting for its incident.

**D-17 · Suppression is a first-class record with a reason code — never a silent
drop.** "Why did my customer not get an SMS?" must be answerable from the
database: quiet hours, opted out, no address for any channel, unknown account,
template missing, variable missing, stale event, epoch fenced, segment cap
exceeded. This is the service's defining invariant.

**D-18 · Persistence → plain SQL over JDBC.** The scaffold requires the choice
to be recorded. The send queue claims work with `FOR UPDATE SKIP LOCKED` and
leases — the identical primitive Core's saga worker uses, and one whose
affected-row count *is* the concurrency guarantee. Running an ORM beside it for
templates would mean two idioms in a service this small.

**D-19 · Send retries: unknowns are retried, with a client reference.** A
gateway timeout is genuinely unknown, and the two failure modes are unequal — a
duplicate debit alert is an annoyance, a missing one is a fraud control that
never fired. Unknowns retry with backoff to a capped attempt count, passing a
client reference so a gateway that supports deduplication can apply it. A
*definite* rejection — malformed number, rejected sender id, invalid address —
is terminal and never retried. This is the opposite of the choice an OTP path
would make, which is part of why OTP is out (D-16).

**D-20 · Consent and opt-out live in Customer, not here.** PRD §4.2 gives
Customer the NDPR consent records. A second consent store is a compliance
liability and an inevitable divergence: two answers to "did this customer agree",
one of them wrong, discovered under audit. Notification reads the preference
through the D-7 lookup and records the suppression.

**D-21 · Transactional templates do not state balances.** Per-aggregate ordering
plus fetch-at-send means a balance rendered into a message may be stale or
arrive after a later one. If a tenant insists, the balance is fetched at send
time and labelled as current-at-send, never as the post-transaction balance.

**D-22 · Tenancy per ADR 0007, in full.** `tenant_id` on every row, composite
foreign keys on `(tenant_id, id)`, row-level security enabled and `FORCE`d,
`SET LOCAL` inside the request transaction, a restricted `NOSUPERUSER
NOBYPASSRLS` role for traffic and a separate owner for migrations. The consumer
path sets tenant context from the envelope's `tenantId`, which ADR 0008
guarantees is always present.

**D-24 · Locale is the customer's, then the tenant's — never a constant.** A
template is keyed by locale and PRD §4.9 wants Hausa, Yoruba and Igbo as tenant
content, so the selection has to come from somewhere: Customer holds the
person's language (Core v1.10), `channel_policy` holds the tenant's default.

The fallback carries the weight. A customer's locale with no published template
falls through to the tenant's default rather than suppressing, so a tenant can
translate one alert at a time without silencing anyone in the meantime. Only
when no preferred locale has a template is it `NO_TEMPLATE`, and the suppression
records which locales were tried — otherwise "no template" is true and useless.

The customer's locale is nullable and the tenant's is not. A person nobody asked
is ordinary; a tenant with no language to fall back to would make the fallback
meaningless.

**D-23 · Identity per ADR 0009.** The admin and query APIs validate tokens via
`libs/auth` and are deny-by-default. The consumer path carries no human
principal; its attribution is the service identity plus the source event id.

---

## Where the detail lives

The tables are in [`data-model.md`](data-model.md), the invariants and suites in
[`testing.md`](testing.md), the endpoint surface in [`api.md`](api.md), and the
pipeline and boundaries in [`architecture.md`](architecture.md). This document
is the index and the decision log — the README is the map, this is the reasoning,
and neither is the territory.

## Known limitations of v1

Stated here rather than discovered later: nothing is actually delivered to a
customer; no delivery receipts and no monetary cost exist; email bounces are
invisible; push is absent; there is no analytics surface; and none of the
operational targets in this document have been measured.

## Appendix — the envelope divergence this design found

Recorded because a design that keeps only its conclusions teaches nothing about
why its guards exist. While specifying D-4, D-5 and D-6, the two publishers were
found to emit different envelopes despite ADR 0008 requiring one: the ledger
published a flat body with the epoch named `ledgerEpoch` and **no event id on
the wire at all** — making the ADR's mandated dedupe key unusable — while Core
published a nested envelope with the epoch named `epoch`, and neither carried
`occurredAt`. Nothing consumed events, so nothing had ever failed.

Fixed before this service was built, in ledger CHANGELOG v1.7 and Core v1.5: one
renderer in `libs/events`, all seven fields, for every publisher. The lesson is
the one ADR 0008 stated in advance — a contract with no consumer is a contract
nobody has tested.

## When the listener throws

Stated, not defaulted (v1.4). Transient failures — Core unreachable, the
database down — retry forever with a fixed backoff: the offset is held, the
event is redelivered, delivery is late and never silently never. A malformed
envelope is the one exception: it is a publisher contract bug (ADR 0008) that
can never succeed, so it is skipped **loudly** — ERROR, with topic, partition
and offset — rather than parking the partition behind a poison message. Spring
Kafka's default (ten retries, then log-and-advance) is exactly the silent loss
this service exists to refuse, which is why `KafkaErrorHandling` replaces it
explicitly rather than trusting the framework's idea of resilience.
