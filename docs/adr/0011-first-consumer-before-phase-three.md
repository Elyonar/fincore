# ADR 0011 — The platform's first event consumer is built before Phase 3

**Status:** Accepted · 2026-08-06
**Supersedes:** nothing. Departs from PRD §9's phase ordering, which the PRD's
own header permits: "where an accepted ADR conflicts with an older PRD section,
the ADR wins until the PRD is revised."

## Context

PRD §9 places Notification in **Phase 3 (Months 4–8)**, and Phase 0 lists
"notifications" among its explicit exclusions. Taken literally, the next work
after Core is Phase 0's remaining surfaces — teller screens, admin UI, product
configuration demo — and no new deployable at all.

Against that sits a fact the phase plan could not have anticipated, because it
was only discovered by trying to build a consumer:

**Nothing has ever consumed an event.** The ledger and Core both publish. The
backbone is real — `libs/events` ships Kafka, RabbitMQ and logging publishers,
and Kafka is what `compose.yaml` starts. But ADR 0008's contract is entirely
one-sided in practice: no service deduplicates on `(publisher, eventId)`, no
service fences an epoch, no service has ever had to survive a redelivery or an
out-of-order arrival.

The cost of that showed up immediately. Designing the first consumer found that
the two publishers emitted **different envelopes** despite ADR 0008 mandating
one: the ledger published a flat body with the epoch named `ledgerEpoch` and no
event id on the wire at all — making the ADR's own deduplication rule
unusable — while Core published a nested envelope with the epoch named `epoch`,
and neither carried `occurredAt`. This is precisely what ADR 0008 predicted:
*"If the envelope is settled per service, two envelopes exist within a month and
every future consumer inherits both, permanently."* It had happened, and nothing
caught it, because catching it requires a consumer.

## Decision

**Notification is built now, ahead of its phase, as the platform's first event
consumer.**

Three properties make it the right first consumer rather than merely the
earliest available one:

- **It is off the money path.** A defect here cannot lose or double a kobo.
  Learning the consumer side on a service that moves money would be the
  expensive way to discover the same lessons.
- **It is small enough to finish.** One schema, one deployable, no rails, no
  scheduler beyond a send queue.
- **Its correctness question is the interesting one.** Not "did it send" but
  "did it send exactly once, and can it say why it did not" — which is the
  consumer-side contract in miniature.

**What this ADR does not do:** it does not promote Notification ahead of Phase 0
in importance, and it does not license further phase reordering. Phase 0's
surfaces remain the path to a pilot. This is one deployable, taken early, for a
reason that is about the platform's contracts rather than about its roadmap.

## Consequences

- **ADR 0008's envelope is now enforced by tests rather than by prose.** One
  renderer in `libs/events`, all seven fields, asserted for every publisher
  (ledger CHANGELOG v1.7, Core v1.5). That fix landed *before* this service, and
  would not have been made without it.
- **Consumer-side machinery is built here first** — durable dedupe on
  `(publisher, eventId)`, epoch fencing, stale-event rejection, tolerance of
  per-aggregate-only ordering — and extracted to `libs/events` when a second
  consumer arrives, per the standing rule that a library follows the second
  consumer rather than anticipating it. Compliance is that consumer.
- **Core gains one endpoint it would otherwise not have needed yet:** an
  account → contact-and-consent lookup. Events carry no PII by design, so a
  consumer must ask. Recorded as a Core amendment.
- **Nothing is delivered to a customer until the messaging connector exists**,
  and connectors are last by decision. v1 ships `log` adapters that announce at
  startup that they deliver nothing. The value taken now is the contract proof
  and the policy — quiet hours, suppression, dedupe — being right before a live
  gateway makes mistakes expensive and public.
- **Phase 3's Notification work is not duplicated, it is moved.** What remains
  for Phase 3 is the connector behind it.

## The alternative that was rejected

**Wait for Compliance & Reporting to be the first consumer**, since ADR 0005
names it the harder constraint — it replays history where notifications care
only about now, and a backbone that serves Compliance serves everyone.

Rejected on sequencing rather than on merit. Compliance's own foundation is
buildable, but its regulatory report pack is blocked on CBN/NDIC specifications
and, per PRD §9 Phase 3, on validation by a pilot's compliance officer. Making
the platform's first consumer a service that cannot be finished would mean
carrying an unproven envelope for longer, on a larger surface. The harder
consumer is better served by a contract already proven against an easier one.

## Revisiting

If Notification turns out to prove less than claimed — if the consumer-side
machinery built here does not generalise to Compliance's replay-based
consumption — then the extraction to `libs/events` will show it, and the honest
correction is to record what did not transfer rather than to assume it did.
