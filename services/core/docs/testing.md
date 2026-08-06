# Core — Invariants & Test Strategy

**Status:** AGREED v1.0 (2026-08-06) — amendments via [`CHANGELOG.md`](CHANGELOG.md)

**Every suite below is unimplemented, because the service does not exist yet.**
Markers (`IMPLEMENTED` / `PARTIAL` / `DEFERRED`) become meaningful when code
lands, and only `IMPLEMENTED` suites gate merges. Stating this once, plainly, is
the alternative to a document that reads as though verification already runs.

## Core's invariants

The ledger's credibility rests on six named, tested invariants. Core needs its
own. Checked per tenant, after every test scenario and on a schedule in
production.

1. **Every saga terminates.** No saga sits in a non-terminal state past its SLA
   without an open ops case. This is a liveness property and the one most likely
   to be quietly violated — a stuck saga holds a limit reservation and a
   customer's expectation.

2. **At most one committed ledger transaction per saga step.** Guaranteed by
   deterministic key derivation, and verified rather than assumed: no two ledger
   transactions may carry keys derived from the same `(saga_id, step)`.

3. **No compensation follows an unknown.** No limit reservation is `RELEASED`,
   and no compensating reversal is issued, while its saga's most recent attempt
   outcome is `UNKNOWN`. This is the protocol's central rule made checkable, and
   it is the invariant whose violation costs money.

4. **Reservations reconcile.** For every `(tenant, subject, window)`,
   Σ `RESERVED` + Σ `CONSUMED` equals the counter, every reservation belongs to a
   saga, and every `COMPLETED` saga has a `CONSUMED` reservation while every
   `FAILED` saga has a `RELEASED` one.

5. **Terminal states are terminal.** No `COMPLETED` or `FAILED` saga ever
   transitions again. Trigger-enforced, not merely asserted in code.

6. **Core and the Ledger agree.** Every `COMPLETED` saga's recorded ledger
   transaction exists in the Ledger, and its entries match the saga's decided
   intent — principal, fee, accounts, currency. Every `FAILED` saga has no
   ledger transaction under its derived key.

   This is the cross-deployable invariant and the most valuable one: it is
   automated shadow-mode comparison in miniature, and it catches a class of bug
   neither service can see alone. It runs as a scheduled reconciliation against
   the Ledger's read API, never against its database.

7. **Attribution is complete.** Every saga records both the principal
   (`initiated_by`) and the service identity (`executed_by`). An examiner asks
   who authorized and which system acted; a saga that cannot answer both is a
   defect, not a gap in the report.

8. **Every automated compensation is self-targeted** *(applies from v2, when
   compensating reversals exist)*. No compensating reversal references a ledger
   transaction its own saga did not record posting, and none exists without a
   `DEFINITE_FAILURE` on a later step of that same saga. This must be an
   invariant rather than a code review, because the path exists precisely to move
   money without a human — and a business reversal must never be reachable
   through it. Corollary: every reversal in the system is either automated and
   self-targeted, or human and carries a consumed approval. There is no third
   kind.

Invariants 1 and 6 need production schedules, not only test assertions. A
violation of 3, 6 or 8 pages; the others alarm.

## The suites

**Failure injection is the primary suite here, not a late addition.** The
service's central claim is "complete or compensated, never partial", and only
injection tests that claim. For the ledger, injection tests hardened a design
that was already provable from its schema; for Core there is no equivalent
structural proof, because the failure window spans a network.

**Failure-injection suite.** Kill the database connection at each phase
boundary — after Phase A commits and before the ledger call; mid-call; after the
ledger commits and before Phase C records it. Assert every case converges, on
recovery, to a state consistent with what the ledger actually holds. Kill the
worker mid-lease and assert another instance reclaims and retries the same key.

**Outcome-classification suite.** A ledger stub returning each response class —
2xx, every 4xx in the ledger's catalog, 5xx, read timeout, connection refused
before send, connection reset mid-response, unparseable body — asserting the
classification in [`outcome-protocol.md`](outcome-protocol.md) exactly. The
connection-refused/read-timeout pair is explicitly tested, because a client
library that collapses them into one exception type silently removes the
distinction the whole protocol depends on.

**Ledger contract suite.** Against the **real** ledger from the compose stack,
never a mock. A mocked ledger leaves the joint contract as unproven as it is
today: same-key replay returns the original result; a 4xx is terminal; a
deliberately duplicated key with a changed payload returns
`IDEMPOTENCY_KEY_REUSED`; a fee-bearing transfer posts as one balanced
transaction; `ALREADY_REVERSED` converges on the winner's id.

**Property-based suite.** Generated sequences of operations and injected
outcomes — transfers, deposits, withdrawals, reversals, crashes, unknowns,
duplicate submissions — must preserve every invariant, with shrinking on
failure. The generator must be able to emit `UNKNOWN` at any step, because that
is the state space example-based tests systematically under-explore.

**Concurrency suite.** Concurrent transfers against one customer's daily limit
(exactly one may breach it); duplicate channel keys racing (one winner, the loser
replays); two workers racing one expired lease (exactly one claims); reservation
sweep racing an active claim.

**Idempotency suite.** Same key + same payload, serially and racing; same key +
different payload → 409; the fingerprint's exclusion of description and
initiating user; key-length bounds; a retry arriving after the original reached a
terminal state.

**Bounds & abuse suite.** Zero and negative amounts; amounts above the platform
cap; wash transfers; oversized keys; a fee that would exceed the principal; a
percentage fee whose rounding is asserted exactly, in integers.

**Module-boundary suite.** ArchUnit: only `core-orchestration` references the
ledger client; no module references another module's internal packages; no
floats; no legacy date API; plus an **empty-import canary**, because every
`no…should…` rule passes when nothing was imported. Database privilege: each
module's role is denied on the other schemas, asserted by attempting a
cross-schema read and requiring it to fail.

**Schema suites.** Presence — every trigger, index, constraint and policy exists
*and* fires, including the append-only triggers on `saga_attempts` and
`product_versions` and the terminal-state trigger on `sagas`. Enforcement — raw
SQL attempts to update a terminal saga, delete an attempt row, or edit a
published product version, all rejected by the database rather than by
application code.

**Tenant-isolation suite.** Per
[ADR 0007](../../../docs/adr/0007-tenant-isolation-pattern.md): cross-tenant
probes on every mutating endpoint; no tenant context ⇒ no rows; and the
**pooled-connection bleed test** — serve tenant A, return the connection, serve
tenant B on the same physical connection, assert A's rows are invisible. A
session-scoped `SET` passes every other test and fails this one.

**Reconciliation suite.** Invariant 6 as a runnable job, with planted
discrepancies: a saga marked complete whose ledger transaction does not exist; a
ledger transaction under a derived key whose saga says failed; entries that do
not match the decided fee. Every planted mismatch must be flagged.

## What is deliberately not tested in v1

Holds, multi-step compensation, partner status queries and inbound events — none
are built, so none are tested. They arrive with the first rails connector.
Listing them here keeps the absence deliberate rather than discovered.

## CI

Every suite runs against real PostgreSQL and, for the contract suite, a real
ledger — never in-memory substitutes. The behaviour under test is the
database's and the ledger's, and a substitute tests neither.
