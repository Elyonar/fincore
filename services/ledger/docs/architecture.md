# Ledger — Architecture & Boundaries

**Status:** AGREED v1.9 (2026-08-08) — amendments via [`CHANGELOG.md`](CHANGELOG.md)

## The shape

```
                 ┌────────────────────────────────────────────┐
   writes        │                LEDGER SERVICE              │      events (outbox → bus)
  ───────────►   │                                            │  ───────────────────────►
  Orchestration  │   REST API ──► Posting engine ──► Postgres │   posting.completed
  (only writer)  │                     │        (sync repl.,  │   posting.reversed
                 │                     │         RPO = 0)     │   account.created/closed
   reads         │                     └──► outbox_events     │   hold.placed/released
  ───────────►   │                                            │
  Reporting      │   sync outbound calls: NONE, EVER          │
  (read-only)    │   events consumed:     NONE, EVER          │
                 └────────────────────────────────────────────┘
```

## Inbound traffic

Synchronous REST only. Exactly **one** writer — Transaction Orchestration
(mTLS + service identity allowlist); Reporting is read-only. Channels never
post directly. Until Orchestration exists, its stand-in is the demo UI and
the test suite; the allowlist discipline starts on day one.

Identity context arrives via validated JWT/service headers. Tenant scoping is
enforced three ways, in depth: application checks, **composite foreign keys
on `(tenant_id, id)`** (cross-tenant references are structurally impossible —
including reversing another tenant's transaction or releasing another
tenant's hold), and PostgreSQL row-level security as backstop. Connections
without a tenant context can read nothing.

**RLS only constrains a role it is allowed to constrain — this comes first.**
PostgreSQL skips policies entirely for a SUPERUSER or a `BYPASSRLS` role, and
exempts a table's own owner unless the table is additionally marked `FORCE ROW
LEVEL SECURITY`. Either exemption makes every policy inert while the catalog
still reports RLS as enabled, so the failure is invisible to inspection. Two
requirements, both load-bearing:

- every tenant-scoped table is `FORCE ROW LEVEL SECURITY`;
- the service connects as `ledger_app` — `NOSUPERUSER`, `NOBYPASSRLS`, DML
  privileges only — while migrations run as the owner. DDL and traffic are
  different jobs and must not share an identity.

Both are asserted by the schema-presence suite, because "RLS is enabled" is not
the same claim as "RLS is enforced".

**The RLS discipline is also pooling-safe — `SET LOCAL`, never
session `SET`.** The tenant context variable is set from the validated token
as `SET LOCAL app.tenant_id` **inside the request's transaction**, so it
reverts automatically on commit or rollback. Connections are pooled and
reused across tenants; a session-scoped `SET` would travel back into the pool
still carrying the last tenant's identity, and the next borrower — possibly
serving a different tenant — would inherit it. That failure mode is
particularly unacceptable here because RLS exists precisely to catch the case
where application code forgot to scope a query: a leaked session variable
makes the backstop fail in the one scenario it was built for. Connection
handoff without an active transaction is therefore forbidden for any
tenant-scoped query. The audit trail records dual
attribution in **two distinct columns** — `initiated_by` (the human
principal or system job) and `executed_by` (the service-chain identity from
the mTLS/service token) — so an examiner sees *who asked* and *which system
acted*, separately (data-model.md).

## Outbound traffic

**Synchronous: nothing, ever.** No HTTP clients, no connector SDKs exist in
this module — the dependency list is itself an architectural control a
reviewer verifies by reading `pom.xml`.

**Asynchronous: transactional outbox, precisely specified.** Events are
written in the same database transaction as the state change.

The relay contract (the naive version loses events — this is binding):

- Poll: `SELECT … FROM outbox_events WHERE published_at IS NULL ORDER BY id
  FOR UPDATE SKIP LOCKED LIMIT N`. **Never a watermark** ("id > last seen"):
  sequence values are assigned at insert, not commit, so a long-running
  posting can commit id=100 *after* id=105 was relayed — a watermark skips it
  forever, silently.
- Mark `published_at` in the same transaction that records the broker ack.
  Delivery is at-least-once; consumers deduplicate on outbox id.
- A partial index on `published_at IS NULL` keeps the poll O(pending).
- **Ordering is per-poll-batch only, not global.** Consumers must be
  state-based — react to an event by fetching current state via the read
  API — never event-sequence-based. This pairs with the thin-payload
  decision below.
- Monitoring: alert on oldest-unpublished age (> 60 s = relay down); a dead
  relay is caught in minutes, not at month-end reconciliation.
- Retention: published rows are purged after 30 days. The ledger's entries
  are the 7-year audit record; the outbox is a delivery queue and must never
  become a second, unaudited event archive.

**The envelope — ADR 0008, rendered by `libs/events` for every publisher.** Each
message body is
`{eventId, eventType, aggregateId, tenantId, occurredAt, epoch, payload}`, with
the thin domain payload nested under `payload`. `eventId` is the outbox row id
and the deduplication key; `occurredAt` is when the state change committed, not
when the relay ran; `epoch` is the restore generation. One renderer rather than
one instruction per service, because two services each assembling "the same"
JSON is how the platform briefly had two envelopes (CHANGELOG v1.7).

| Event emitted | When | Payload |
|---|---|---|
| `account.created` / `account.closed` | lifecycle | thin: ids, type, currency |
| `posting.completed` | transaction committed | thin: tx id, key, entry count |
| `posting.reversed` | reversal committed | thin: original + reversal ids |
| `hold.placed` | hold created | thin: ids, amount, expiry |
| `hold.released` | released / expired / consumed | thin + reason |

**Thin payloads — decided.** Ids plus the minimal summary shown above;
consumers fetch details via the read-only API. Safer for PII, and it *forces*
the state-based consumer pattern that the ordering contract requires anyway.
Where a payload does carry an amount (hold placement and release), it is
serialized as a decimal string — the same rule the read API follows (api.md),
so a consumer parsing events and a consumer parsing responses never disagree.

## Events consumed: none

The ledger subscribes to nothing; it is command-driven only.

This survives the tenant registry intact. `tenants` is a local projection of the one fact the
ledger needs — may this tenant transact — written by provisioning rather than by a subscription.
Consuming a tenant feed would make the ability to accept money depend on a message having arrived,
and a missed message would leave a real bank silently unable to post. Provisioning that fails is
visible to the operator running it, immediately. Its behaviour is
fully determined by the API calls it receives — fully testable, replayable,
auditable. Everything that reacts to money (notifications, compliance,
reporting, AI) subscribes downstream.

## Durability & disaster recovery (ledger-specific, stricter than platform)

- **Synchronous replication: RPO = 0 for acknowledged commits.** The platform
  NFR of "RPO ≤ 5 min" is explicitly overridden here: losing five minutes of
  committed postings *is* money being wrong, while already-published outbox
  events would "prove" states the restored ledger denies and the rewound
  idempotency registry would let replays re-post under fresh transaction ids.
- **Restore protocol (for the disaster beyond replication):** every published
  event carries a ledger epoch; on restore the epoch increments; consumers
  discard events from newer-than-restored epochs and reconcile via the read
  API; Orchestration replays its outstanding window (safe: idempotent).
  Restore drills are part of the test strategy (testing.md), not an NFR
  footnote.

## Never in this service

Fee logic, product rules, external calls, orchestration/workflow, event
consumption, customer PII. If it needs to know *why* money is moving, it does
not belong in the ledger.

## Non-functional targets

| Concern | Target |
|---|---|
| Posting commit latency | p99 < 200 ms |
| Throughput | 200 TPS sustained per tenant cluster; hot-account benchmark floor in CI (testing.md); fan-in sharding pre-designed (posting-algorithm.md) |
| Durability | RPO = 0 (sync replication); RTO ≤ 1 hr; quarterly restore drills |
| Invariant violations in production | 0, ever — kept meaningful by the violation / authorized-exposure split (testing.md) |
