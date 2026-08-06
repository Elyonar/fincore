# Core — Architecture & Boundaries

**Status:** AGREED v1.4 (2026-08-06) — amendments via [`CHANGELOG.md`](CHANGELOG.md)

## The shape

```
                        ┌──────────────────────────────────────────────┐
  channels              │                CORE (one deployable)         │
 ─────────────►         │                                              │
  teller, API           │  ┌────────────┐  ┌───────────┐               │
  (validated JWT)       │  │  customer  │  │  product  │              │
                        │  │ schema:     │  │ schema:    │              │
                        │  │ customer    │  │ product    │              │
                        │  └─────▲──────┘  └─────▲──────┘               │
                        │        │ interface     │ interface            │
                        │  ┌─────┴───────────────┴──────┐               │
                        │  │       orchestration        │               │
                        │  │    schema: orchestration   │───────────────┼──► LEDGER
                        │  │    the only ledger client  │  mTLS +       │    (write API)
                        │  └────────────┬───────────────┘  service id   │
                        │               └──► outbox_events (per module) │
                        └──────────────────────────────┬───────────────┘
                                                       └──► event backbone
```

## Modules and ownership

| Module | Schema | Owns | Never |
|---|---|---|---|
| `customer` | `customer` | Profiles, KYC tier, lifecycle status, mandates, customer↔account mapping | Balances, entries, transaction history, money of any kind |
| `product` | `product` | Product catalog, fee rules, limit rules, versioned configuration | Executing anything. It returns *decisions*, never postings |
| `orchestration` | `orchestration` | Sagas, limit reservations, fee application, the ledger client, the idempotency registry | Fee or interest *rules*; customer PII; balance computation |

`app` assembles them into one Spring Boot application. It holds wiring and
cross-cutting infrastructure (the outbox relay, the saga worker), no domain
logic.

### How the boundaries are enforced

Three mechanisms, deliberately overlapping, because a boundary defended one way
is a boundary that erodes. It was four until v1.1 traded the api/impl module
split for fewer directories; the CHANGELOG records what that cost:

1. **The POM dependency graph.** `orchestration` depends on `customer` and
   `product`; nothing depends on `orchestration`. Direction is declared, so a
   cycle is a build failure rather than a review comment.
2. **Database privilege.** Each module connects as its own role, granted only on
   its own schema ([ADR 0007](../../../docs/adr/0007-tenant-isolation-pattern.md)).
   A cross-schema query fails at runtime, in the test suite, on first attempt.
3. **ArchUnit.** Only `orchestration` may reference the ledger client;
   no module may reference another module's internal packages; the platform hard
   rules (no floats, no legacy date API) apply throughout.
4. **The POMs.** Dependency direction is declared, so a cycle is a build failure
   rather than a review comment.

## Inbound traffic

Synchronous REST/JSON from channels. Every request carries a validated identity
context ([ADR 0009](../../../docs/adr/0009-service-to-service-identity.md)):

- **The tenant comes from the token**, never from a header. A header-supplied
  tenant is a caller assertion, and if it is wrong every downstream isolation
  control faithfully enforces the wrong boundary.
- **The principal** — the human or system that asked — is carried through to the
  ledger as `initiated_by`.
- **The service identity** — Core itself — is what the ledger's allowlist
  verifies, and is recorded as `executed_by`.

Tenant scoping is enforced in depth exactly as the ledger does it: application
scoping, composite foreign keys on `(tenant_id, id)`, and forced row-level
security under a non-bypassing role with `SET LOCAL` tenant context inside the
request transaction. Connections are pooled across tenants, so a session-scoped
`SET` would hand the next borrower the previous tenant's identity — the failure
row-level security exists to catch.

## Outbound traffic

**The Ledger, and nothing else, in v1.** No rails connectors, no SMS, no KYC
providers. The dependency list is an architectural control: an HTTP client
appearing in `customer` or `product` is a boundary violation, not a
convenience.

**The ledger client lives only in `orchestration`** and is the module's
sole outbound dependency. It:

- derives idempotency keys as a pure function of `(saga_id, step)`
- preserves the distinction between "nothing was sent" and "we do not know",
  because the outcome protocol depends on it
- never retries with a fresh key, under any circumstances

**Every outbound call is preceded by persisted state.** The saga row commits
before the first call, so a crash in the window is recoverable rather than an
orphan posting nobody recorded.

## Events

**Published**, per [ADR 0008](../../../docs/adr/0008-event-contract.md), each
module writing to its own outbox table in its own schema, in the same
transaction as the state change:

| Event | Module | When |
|---|---|---|
| `transfer.initiated` | orchestration | Saga accepted and reserved |
| `transfer.completed` | orchestration | Posting confirmed |
| `transfer.failed` | orchestration | Definite failure, compensated |
| `transfer.reversed` | orchestration | Reversal confirmed |
| `customer.created`, `customer.kyc_tier_changed`, `customer.status_changed` | customer | Lifecycle |
| `product.published`, `pricing.changed` | product | Configuration published |

**Core never restates the ledger's facts.** The ledger publishes
`posting.completed`; Core publishes `transfer.completed`. They describe the same
business moment from different vantage points and are not interchangeable. Two
publishers of one fact means consumers eventually disagree about which is
authoritative.

**Consumed: nothing, in v1.** Core is command-driven, like the ledger. This
changes with the first rails connector, when inbound credit notifications
arrive — and that is when consumer-side deduplication and epoch fencing get
built. Saying "none, in v1" rather than "none, ever" is deliberate: the ledger's
boundary is permanent, Core's is a scope statement.

**One relay reads all three outbox tables**, running in `app` under a role
granted on the outbox tables only. The relay contract is the platform's: poll
`FOR UPDATE SKIP LOCKED` on unpublished rows ordered by id, never a watermark,
mark published in the transaction that records the broker acknowledgement, alert
on oldest-unpublished age.

## Durability & disaster recovery

Core's state is recoverable, but it is **not** the source of monetary truth — the
ledger is. That asymmetry sets the posture:

- **RPO ≤ 5 min** (the platform NFR) is acceptable here, where the ledger
  requires RPO = 0. Losing five minutes of Core state loses saga records, not
  money: the postings survive in the ledger, and reconciliation
  (`testing.md`, invariant 6) rebuilds the relationship.
- **Restoring Core past a window is a reconciliation event, not a replay
  event.** Sagas that completed in the lost window have ledger transactions
  whose keys are deterministic, so their status is recoverable by asking the
  ledger rather than by guessing.
- Core carries an epoch on its published events for the same reason the ledger
  does: a consumer must be able to distrust events from a window the publisher
  was rewound past.

## Never in this deployable

External connectors. Channel-specific API shapes or UI logic. Balance
computation or storage. Interest accrual. Lending workflows. Report generation.
Anything that writes to another deployable's database.

## Non-functional targets

| Concern | Target |
|---|---|
| End-to-end intra-tenant transfer | p99 < 1 s (PRD §7), of which the ledger commit is < 200 ms |
| Throughput | 200 TPS sustained per tenant cluster |
| Durability | RPO ≤ 5 min, RTO ≤ 1 hr |
| Horizontal scale | Stateless; all work claimed from the database with leases, never held in a JVM queue |
| Saga liveness | No saga non-terminal beyond its SLA without an ops case |

### Database connections under load

One pool per module, each sized deliberately rather than left at the driver's
default: the owner connection runs migrations and never serves a request, the
worker and relay are background batch work, and the money path takes the largest
share. A short connection-timeout on that path is **backpressure, not
impatience** — a saturated pool must shed load as a 503 the caller retries
rather than queue unboundedly behind a database that is already the bottleneck.
Timing out *before* the Ledger call is safe by construction: nothing was sent,
so the outcome protocol reads it as a definite failure rather than an unknown.

Scaling out multiplies pools by instances, and a PostgreSQL connection is a
backend process — throughput peaks at a couple of dozen *active* connections and
degrades beyond it. So the answer at scale is a **transaction-mode pooler**, not
a higher `max_connections`. This platform can use one, and that is not luck: two
decisions taken for other reasons make it possible.

- Tenant context is `SET LOCAL`, which is transaction-scoped. A session-level
  `SET` — the obvious alternative until pooled-connection bleed ruled it out —
  would make transaction pooling unusable.
- No transaction is held across an outbound call, so a server connection is
  never pinned for the duration of someone else's outage.

Raising a database's connection ceiling is a local convenience for running the
stack and the test suite together. It is not the production answer, and
`compose.yaml` says so where it does exactly that.

**These are targets, not measurements.** Nothing here has been benchmarked, and
this table stays labelled as intent until it has been.
