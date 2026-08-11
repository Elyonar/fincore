# Core — Invariants & Test Strategy

**Status:** AGREED v2.0 (2026-08-11) — amendments via [`CHANGELOG.md`](CHANGELOG.md)

**Suites are marked `IMPLEMENTED` / `PARTIAL` / `DEFERRED` individually, and only
`IMPLEMENTED` ones gate merges.** An unmarked suite is not yet written. This
header once said every suite below was unimplemented because the service did not
exist; several now are, and leaving that sentence in place would have made the
document wrong in the direction that flatters — which is the failure the markers
exist to prevent.

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

## Where the suite's data lives

**The suite runs against `core_test`, never `core`.**

A running deployable is a concurrent writer, and a test cannot defend itself
against one. Core is the more instructive case, because its interference reached
somewhere nobody would look for it: the saga worker and the outbox relay poll on
short intervals, and PostgreSQL transaction ids are **cluster-wide**, so Core's
polling of the `core` database held the *Ledger's* quiesce horizon below entries
the Ledger's own tests had already committed. A Core process, a Core database,
and a failing Ledger test.

It looked like a Ledger defect. It was not. The Ledger's tests were taught to
wait for the horizon, which was correct but treated a symptom; when a second,
unrelated failure appeared there for the same underlying reason, that was the
signal the shared database was the problem rather than any assertion — draining
or waiting cannot help against a writer that fires *after* you drain. The Ledger
side of the story is in its own [`testing.md`](../../ledger/docs/testing.md).

Flyway builds the test database from the same migrations, so the two cannot
drift. `SPRING_DATASOURCE_URL` overrides the default. `db/init` runs only on an
empty volume, so an existing checkout needs the database created in place — see
`README.md`.

## The suites

**Failure injection is the primary suite here, not a late addition.** The
service's central claim is "complete or compensated, never partial", and only
injection tests that claim. For the ledger, injection tests hardened a design
that was already provable from its schema; for Core there is no equivalent
structural proof, because the failure window spans a network.

**Failure-injection suite — IMPLEMENTED (v1.14),** as `FailureInjectionTest`:
each crash window driven to convergence — after Phase A commits and before the
ledger call; mid-call with the outcome unknown; after the ledger commits and
before Phase C records it; and a worker dying mid-lease, reclaimed by another
under the same key. The crash is simulated by construction (a saga opened and
never driven *is* the after-Phase-A crash; a stub answering 500 *is* the lost
response); what a literal connection kill adds — mid-statement termination
inside Phase A — is PostgreSQL's transaction atomicity, which the ledger's
suite already exercises. The repeated assertion is the load-bearing one: every
recovery path re-sends the original derived key.

**Outcome-classification suite.** A ledger stub returning each response class —
2xx, every 4xx in the ledger's catalog, 5xx, read timeout, connection refused
before send, connection reset mid-response, unparseable body — asserting the
classification in [`outcome-protocol.md`](outcome-protocol.md) exactly. The
connection-refused/read-timeout pair is explicitly tested, because a client
library that collapses them into one exception type silently removes the
distinction the whole protocol depends on.

**API surface catalog.** *(IMPLEMENTED — `ApiSurfaceCatalogTest`.)* Parses the
endpoint table in [`api.md`](api.md) and the generated OpenAPI document, and
asserts each contains the other. Modelled on the Ledger's
`ErrorCodeCatalogTest`, and added because the absence of it was expensive:
`api.md` documented sixteen endpoints while six existed, and the only surface
assertion in the suite was a positive spot-check for two known paths. **A
positive check cannot find an absence** — that is the whole lesson, and it
generalises past this file. Carries an empty-set canary, because a parser that
silently stops matching turns a bidirectional check into a decoration that
passes.

**Administrative-surface suites.** *(IMPLEMENTED — `CustomerApiTest`,
`ProductApiTest`, `CashAndReversalApiTest`.)* Over real HTTP, through the whole
filter chain. Two assertions carry more weight than the rest: a KYC tier change
is refused without a reason and recorded in an append-only trail, and the author
of a product version cannot publish it. Both are money controls — a tier is a
ceiling, a product version holds the fee and the limit — so both are written as
tests that must be deleted deliberately and visibly if anyone ever decides
otherwise.

**Ledger contract suite.** *(IMPLEMENTED — `LedgerContractTest`, tagged
`contract` and excluded from the default build.)* Against the **real** Ledger,
never a mock: same-key replay returns the original result; a 4xx is terminal; a
duplicated key with changed money returns `IDEMPOTENCY_KEY_REUSED` classified as
*our* bug; a fee-bearing three-entry transfer posts as one balanced transaction;
`ALREADY_REVERSED` converges on the winner's id.

```bash
docker compose up -d ledger
./mvnw -pl services/core/app -am -Pcontract test \
       -Dfincore.contract.tenant-id=<a tenant registered in the Ledger>
```

Excluded from the default build rather than skipped at runtime. A test that
quietly passes when its subject is absent is worse than one that is not run,
because the report then says the contract was checked.

**It earned itself on the first run**, which is the argument for writing it at
all. Two defects that every stub-based test in this module was blind to:

- **The client never sent `X-Tenant-Id`.** Core could not talk to a real Ledger
  at all — every posting would have been refused. The stub accepted anything, so
  nothing caught it.
- **`ALREADY_REVERSED` classified as `UNKNOWN`.** The Ledger carries the winning
  reversal's id in `detail`; the client looked only at `reversalTransactionId`.
  A losing reversal would have retried forever instead of settling on the winner.

Both are the same shape of failure: two services each internally consistent and
wrong about the other. Neither service's own suite can see it.

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

**Module-boundary suite.** ArchUnit: only `orchestration` references the
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

**Reconciliation suite — IMPLEMENTED (v1.14),** as `ReconciliationTest`
against the scheduled job in `internal/reconcile`: planted discrepancies — a
completed saga the ledger never saw, debits that disagree with the decided
amount — are each flagged exactly once (append-only finding + one ops case,
however many runs re-observe them), and an unreachable ledger records nothing.
**Deliberately not covered:** a ledger transaction whose saga says FAILED.
Detecting it needs a read-by-idempotency-key on the Ledger's API, which does
not exist; re-posting the key to find out could *make* it true. That half of
the invariant waits on a ledger amendment and is recorded here rather than
implied covered.

**Organization & trust-boundary suites (v1.13).** `OrgUnitApiTest` — the tree
over HTTP: creation, parents, closure, assignment/revocation, deny-by-default,
codes never recycling, and another tenant's tree being invisible rather than
forbidden. `TillAndChannelApiTest` — the two inputs that stopped being caller
assertions: a till refuses an unknown, closed or non-BRANCH code
(`UNIT_NOT_FOUND`), and a transfer's channel costs the matching `channel:*`
permission, with an unmodelled channel malformed rather than licensable; all
Phase A refusals, asserted with no stub ledger running. `DailyLimitAndFeeConfigTest`
— the day accumulates: a second transfer that fits PER_TXN but breaches the
summed window refuses with `DAILY_LIMIT` and rolls back whole (no saga, no
reservation, no event), a smaller amount still fits, and the fee credits the
account the *product* names — asserted against the posting the stub ledger
actually received and the saga row a worker retry would rebuild from.

**Lending suites (v1.16).** `ScheduleEngineTest` — a seeded 500-case sweep per kind proving the
sums exact, components non-negative, dates monotone and grace principal-free, plus golden vectors
(annuity level-payment, flat totals, bullet ACT/365, zero-rate). `LendingApiTest` — the spectrum
property (zero-tier auto-approval attributed to the policy; the applicant refused as signer;
duplicate signers refused by the index; the two-signature chain generating the offer; the
unconfigured tenant degrading to one human, never to auto-approval), the full lifecycle to
closure, over-payoff refused at intake, refused disbursement returning to ACCEPTED, deny-by-
default and cross-tenant invisibility. `LendingJobsAndSchemaTest` — ACT/365 to the kobo with
idempotent reruns, bucket edges exact, recovery transitions, and the evidence tables refusing
edits.

**Lending v1.17 additions.** Penalty accrual: the flat charge lands once per late installment and
the daily charge advances `penalty_through` exactly (same-day rerun charges zero); the cap binds;
allocation reaches the `PENALTY` component in the configured order and payoff includes penalty
due, with `REPAYMENT_EXCEEDS_PAYOFF` refusing a kobo above it. Recognition: an allocated
repayment's interest lands in the configured income account as a `RECOGNITION` saga under the
per-repayment derived key, replays converge (job rerun posts nothing twice),
`recognized_interest_minor` advances only by posted amounts, and an unconfigured version resolves
as a recorded no-op. Funding account: the configured `loan_rules.funding_account_id` overrides
the caller's on disburse.

**UI-runway suites (v1.19).** `UiReadsApiTest` — every screen-opener from `ui-runway.md` §3
with the standing probes: search by name and reference with keyset pages that stay disjoint and
ordered under an opaque cursor; balances joined from the ledger with "could not ask" answered as
503 and never as zero; the statement passed through byte-identical including a 404; the till's
day with a consistent net; the checker's queue; the loan desk's awaiting-me filter reproducing
the approve guard's own conditions; deny-by-default and cross-tenant emptiness on all of them.
`JwtEndToEndTest` — Core in jwt mode against a local JWKS: dev headers inert, health open,
missing permission 403, and a full disbursement on real tokens with the outbound side asserted —
the ledger stub must receive Core's service credential and the forwarded user bearer. The
ledger's own `LedgerJwtAuthTest` covers the receiving half.

## What is deliberately not tested in v1

Holds, multi-step compensation, partner status queries and inbound events — none
are built, so none are tested. They arrive with the first rails connector.
Listing them here keeps the absence deliberate rather than discovered.

## CI

Every suite runs against real PostgreSQL and, for the contract suite, a real
ledger — never in-memory substitutes. The behaviour under test is the
database's and the ledger's, and a substitute tests neither.
