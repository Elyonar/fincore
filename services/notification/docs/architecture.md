# Notification — Architecture & Boundaries

**Status:** AGREED v1.3 (2026-08-06) — amendments via [`CHANGELOG.md`](CHANGELOG.md)

## The shape

```
   event backbone                ┌──────────────────────────────────────────┐
  ────────────────►              │           NOTIFICATION                   │
   fincore.core.transfer.*       │                                          │
                                 │  intake ──► policy ──► template ──► queue│
                                 │    │          │           │          │   │
                                 │  dedupe    channel      render     claim │
                                 │  fence     consent      units      lease │
                                 │  stale     quiet hours                   │
                                 │                                     │    │
   CORE ◄────────────────────────┤  contact lookup (by account)        │    │
   /v1/customers/by-account/{id} │                                     ▼    │
                                 │                            MessageSender │
                                 └──────────────────────────────────┬───────┘
                                                                    └──► messaging
                                                                         connector
                                                                         (not built)
```

Every branch that does not end in a message ends in a **suppression row**. There
is no third outcome, and that is the property the whole design is arranged
around.

## Ownership

| Owns | Does not own |
|---|---|
| Templates and their versions | Customer profiles or contact details — Customer's, fetched per send |
| Delivery policy: categories, channel order, quiet hours | Consent records — Customer's (PRD §4.2) |
| The channel registry | Gateway credentials or sender ids — the messaging connector's (PRD §4.6) |
| The send queue, attempts and suppressions | Any money, balance or posting |
| Consumer-side dedupe, epoch fencing, staleness | The event contract itself — ADR 0008's |

## Inbound

**Events, from the backbone.** The only inbound path that produces messages.
Consumed with `auto-offset-reset: latest` and never `earliest` — Kafka retains
history for Compliance's benefit while notifications care only about now
(ADR 0005), so a consumer starting at the beginning of a retained topic would
send weeks of stale alerts on its first deployment.

**HTTP, for administration.** Templates and policy, and a read of delivery state.
Deny-by-default, tokens validated through `libs/auth`, tenant from the token and
never a header (ADR 0009). The consumer path carries no human principal: its
attribution is the service identity plus the source event id.

## Outbound

**Core, and the messaging connector. Nothing else, ever.**

Core is asked two questions on every send: which accounts a transaction moved
between, and — for each side — who holds that account, what language they speak
and what they agreed to. Event payloads carry no PII by design (ADR 0008), so a consumer
cannot read an address off the bus; and having asked, it does not keep the
answer, because Core's customer schema is documented as the only home for a
customer profile.

**An unreachable Core is not "no such customer".** The directory throws rather
than answering empty, the event stays unconsumed, and redelivery retries it.
Answering empty would turn a Core outage into a stream of `NO_ADDRESS`
suppressions — messages permanently not sent, each with a recorded reason that
is false.

**No ledger client. No gateway SDK.** The POM is where a reviewer checks both,
and it says so.

## The pipeline, and where it stops

| Stage | Stops when | Suppression |
|---|---|---|
| Dedupe | `(publisher, event_id)` already recorded | — the event is simply not reprocessed |
| Epoch fence | epoch newer than the trusted generation | `EPOCH_FENCED` |
| Staleness | `occurredAt` older than `max-event-age` | `STALE_EVENT` |
| Contact | no live customer holds the account | `UNKNOWN_ACCOUNT` |
| Policy | tenant configured no channels for the category | `NO_POLICY` |
| Channel selection | no address for any configured channel | `NO_ADDRESS` |
| Consent | the customer said no for this category and channel | `OPTED_OUT` |
| Quiet hours | inside the window, and not transactional | `QUIET_HOURS` |
| Template | no published version for the key and channel in **any** preferred locale — the customer's, then the tenant's default | `NO_TEMPLATE` |
| Render | a variable the event did not carry | `MISSING_VARIABLE` |
| Measure | over the channel's `max_units` | `TOO_MANY_UNITS` |
| Send | attempts exhausted without a definite outcome | `ATTEMPTS_EXHAUSTED` |

## Events

**Published: none, in v1** (design D-2). A notification is not a domain fact
anyone's state depends on, and publishing would cost an outbox, a relay and a
retention job to serve no consumer. When Compliance needs "was the customer
told", `notification.sent` / `notification.failed` arrive as a contract change
recorded in [`CHANGELOG.md`](CHANGELOG.md).

**Consumed:** Core's business events. Deliberately *not* the ledger's postings —
see [`design.md`](design.md) D-3, which is the decision this service most
depends on getting right.

## Concurrency

Several instances run behind a load balancer, and the send queue is claimed from
the database with `FOR UPDATE SKIP LOCKED` and a lease — never an in-JVM queue,
which loses work on restart and duplicates it across replicas. This is Core's
saga-claim primitive, reused deliberately: one idiom learned once.

A lease that expires while a send is genuinely in flight causes a duplicate
*attempt*, never a duplicate *message*, because the unique key on
`(tenant, business_moment_key, category, channel, recipient)` absorbs it.

## Durability & disaster recovery

Notification holds no money and no record anyone reconciles against, so its
posture is the lightest on the platform:

- **RPO ≤ 5 min, RTO ≤ 1 hr.** Losing five minutes of state loses delivery
  history, not money.
- **A restore past a window replays nothing.** The consumer resumes from its
  committed offset; events inside the lost window are either redelivered — and
  deduplicated against whatever survived — or dropped as stale by the age guard,
  which is the correct outcome for an alert nobody can act on any more.
- **Suppression history is the audit surface**, and it is append-only in
  practice: nothing in the service updates a suppression row.

## Non-functional targets

| Concern | Target |
|---|---|
| Intake | one event handled well inside a broker poll interval |
| Send queue | drained continuously; no batch window (constitution #9) |
| Horizontal scale | stateless; all work claimed from the database with leases |
| Delivery guarantee | at-least-once per business moment, at-most-once success |

**These are targets, not measurements.** Nothing here has been benchmarked, and
this table stays labelled as intent until it has been.

## Never in this deployable

Money movement of any kind. Gateway credentials or sender ids. Customer profile
data or consent records. OTP delivery — that path runs from Keycloak's SPI
straight to the messaging connector (PRD §4.10), bypassing notification policy
deliberately, because OTP wants the opposite retry rule (design D-16, D-19).
