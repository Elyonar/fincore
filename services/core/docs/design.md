# Core — Design Index & Decision Log

**Status:** AGREED v1.24 (2026-08-10) — implemented from here; amendments via
[`CHANGELOG.md`](CHANGELOG.md) and the [design-change convention](../../../docs/conventions/design-changes.md).
**Source:** platform PRD §4.2 (Customer), §4.3 (Product), §4.4 (Orchestration),
§3 (constitution), §5 (communication map), §6 (security), §7 (NFRs), §8
(testing); [ADR 0006](../../../docs/adr/0006-modular-core.md) (packaging),
[ADR 0007](../../../docs/adr/0007-tenant-isolation-pattern.md) (tenancy),
[ADR 0008](../../../docs/adr/0008-event-contract.md) (events),
[ADR 0009](../../../docs/adr/0009-service-to-service-identity.md) (identity).

## The design, by topic

| Topic | Doc |
|---|---|
| Modules, boundaries, traffic, the ledger client, DR posture | [`architecture.md`](architecture.md) |
| Tables per schema, ownership rules, decided edge cases | [`data-model.md`](data-model.md) |
| Endpoint surface, error catalog, contract properties | [`api.md`](api.md) |
| Saga execution, recovery, compensation, the claim protocol | [`saga-protocol.md`](saga-protocol.md) |
| **The three-valued outcome model** — the doc this service exists to get right | [`outcome-protocol.md`](outcome-protocol.md) |
| Core's invariants and every test suite | [`testing.md`](testing.md) |
| Amendments since this design was agreed | [`CHANGELOG.md`](CHANGELOG.md) |

This design is **AGREED**. Changes to it are amendments recorded in
[`CHANGELOG.md`](CHANGELOG.md), in their own PR, ahead of the code — never
silent edits. Code that contradicts it is a bug even if it works.

## One paragraph

Core is one deployable holding four domain modules — `customer`, `product`, `organization`,
`orchestration` — each owning a schema in one PostgreSQL database and a
database role granted only on that schema. Orchestration turns a business
intent into a balanced, attributed, idempotent posting against the Ledger, and
guarantees that a request interrupted at any point ends either completely done
or completely undone. It is the **only** caller of the Ledger's write API and
the only module that declares the ledger client. v1 covers book transfers only:
deposit, withdrawal, intra-tenant transfer, reversal, status lookup. No external
rails, no holds, no standing orders.

## Decision log

Format: decision · resolution · rationale. Superseded entries are annotated,
never deleted.

**Packaging → four domain modules, one deployable, one database, one schema each.**
Recorded in [ADR 0006](../../../docs/adr/0006-modular-core.md). The decisive
argument is not operational cost but correctness: the limit reservation and the
saga row must commit in one local transaction, which separate databases would
turn into a distributed protocol on the money path.

**Saga engine → in-house, persisted in PostgreSQL. Temporal and Camunda
rejected for v1.** The v1 flow set is small, closed, and request-scoped. A
workflow cluster brings its own datastore, its own operational burden and its
own programming model, against a codebase whose established idiom is reviewable
SQL in Postgres — the same idiom that made the ledger auditable. Revisit when
workflows become genuinely long-running (schedules spanning months) or when
workflow versioning becomes the dominant cost; that would be an ADR.

**v1 places no holds.** A hold reserves funds across an external call whose
outcome is not yet known. Every v1 flow is a single atomic ledger posting with
no external call between decision and commit, so a hold would reserve funds
against nothing. The hold protocol is designed in
[`saga-protocol.md`](saga-protocol.md) but not built; it activates with the
first rails connector. Stated explicitly because "we have holds" is the kind of
claim that gets assumed rather than checked.

**The saga machinery is still justified for single-step flows.** Not because
the flow branches, but because the ledger call can time out. An unknown outcome
needs persisted state, a deterministic retry key and a recovery worker
regardless of how few steps precede it.

**Outcome model → three values, never two:** `SUCCESS`, `DEFINITE_FAILURE`,
`UNKNOWN`. Compensation is legal only from `DEFINITE_FAILURE`. Collapsing
`UNKNOWN` into failure and compensating produces a double credit when the
original turns out to have succeeded; collapsing it into success loses money.
This is the single decision most responsible for whether this service is
correct. See [`outcome-protocol.md`](outcome-protocol.md).

**Ledger idempotency keys → a pure function of `(saga_id, step)`, never random
and never time-derived.** The Ledger's contract binds its caller to retry the
*same* key on an unknown outcome. A random key per attempt would make that
contract unsatisfiable and would double-post precisely in the case it exists to
prevent. Format: `core:{saga_id}:{step}` — well inside the 200-character cap.

**The saga row is persisted before the first outbound call, always.** A crash
between "call the ledger" and "record that we called" is otherwise
unrecoverable: an orphan posting with no record that Core made it.

**Fees → additional entries in the same ledger transaction, not a second
transaction.** The Ledger accepts 2..100 entries balanced per currency, so
atomicity is free: debit the customer principal + fee, credit the counterparty
principal, credit fee income. One idempotency key, and reversal semantics come
out right by construction — undoing the transfer undoes its fee. Fees on
*failed* operations, where a tenant charges them, are a separate transaction
posted after `DEFINITE_FAILURE`.

**Limits → reservations, not checks.** A check-then-post races: two concurrent
transfers each observe the limit unbreached and both proceed. A reservation is
taken in the same local transaction that creates the saga, converted to consumed
on success, released on definite failure or expiry.

**Product returns decisions, never postings.** It answers "is this permitted,
what fee, what limit, under which configuration version" and Orchestration turns
that into entries. The version is recorded on the saga so a past decision is
reconstructible even after the configuration changes.

**Customer owns identity and KYC state; it never owns money.** Balances,
entries and transaction history live in the Ledger. Customer holds the profile,
tier, status, mandates and the customer↔account mapping.

**Persistence → plain SQL over JDBC in `orchestration`. Deferred, per module,
for `customer` and `product`.** The scaffold convention requires every service to
record this choice rather than let it be inferred from whatever got written
first.

*Orchestration: JDBC, for the ledger's reasons.* Its correctness is written in
terms of things an ORM takes away — `FOR UPDATE SKIP LOCKED` for claiming, a
conditional `UPDATE … WHERE claimed_by = ?` whose affected-row count *is* the
concurrency primitive, unique-index conflicts as the idempotency arbiter, and a
Phase A whose statement order is deliberate. An ORM decides statement order at
flush time and its identity map can serve a pre-lock copy of a row just locked.
There is also an audit argument: a reader can follow the saga engine and see
every statement that moves money.

*Customer and product: not yet decided, deliberately.* Both are CRUD-shaped and
JPA is a reasonable fit — Customer has real relationships and needs field-level
PII encryption, Product is a version graph loaded and cached together. But both
modules currently have no tables and no code, and choosing an ORM before there
is a schema to map is choosing on anticipation. The decision belongs with the
first real schema, and the module boundary is what keeps it contained to one
module.

Two constraints on that later choice, recorded now because they are easy to miss:
per-module database roles mean a DataSource per module, so an ORM multiplies
configuration rather than adding one instance; and Phase A is a single
transaction spanning all three modules, in which customer and product are
**read-only**. Should either ever write during Phase A, the flush-ordering
argument above starts applying to it too, and JDBC becomes the answer there as
well.

**The saga worker claims work from the database with leases — never an in-JVM
queue.** Core runs multiple instances behind a load balancer. An in-memory queue
loses work on restart and duplicates it across replicas. Claims use
`FOR UPDATE SKIP LOCKED` with an expiry, so a dead instance's work is reclaimed
rather than stranded.

**Each module owns its own outbox table; one relay reads all three.** Events
belong to the module whose state changed. The relay is cross-cutting
infrastructure with a role granted on the outbox tables only — the same shape
the ledger's relay access already takes.

**An unknown outcome is reported to the caller as 5xx, never as `202
Accepted`.** A 202 is success-shaped: it invites a channel to record "submitted"
and move on, when the protocol needs the caller to keep asking. A 5xx triggers
exactly the same-key retry obligation Core publishes, and that retry is what
resolves the saga. `GET /v1/transactions/{id}` exists for non-mutating recovery.

**Tenant comes from the validated token, never from a header.** Per
[ADR 0009](../../../docs/adr/0009-service-to-service-identity.md). A
header-supplied tenant is a caller assertion; if it is wrong, every downstream
isolation control faithfully enforces the wrong boundary.

**Cross-module calls go through published interfaces, enforced by the
classpath.** `orchestration` depends on `core-customer-api` and
`core-product-api`, never on their implementations. Nothing depends on
`orchestration`.
*— Superseded by v1.1.* The rule stands; the enforcement changed. One Maven
module per domain replaced the api/impl split, so a module's `api` package is
the published surface and its `internal` package is private, enforced by
`ModuleBoundaryTest` and by per-schema database roles rather than by the
compiler. See [`CHANGELOG.md`](CHANGELOG.md).

**The service identity the Ledger sees is `core`, not a module.** Mutual TLS
authenticates a *process*, and all three modules share one. So the Ledger's
caller allowlist admits `core`, and the narrower rule — only `orchestration` may
hold the ledger client — is enforced inside Core by ArchUnit, because no
certificate can distinguish modules within a single process. Worth stating
because "the ledger accepts writes only from orchestration" is otherwise read as
something the ledger verifies, when it is something Core guarantees.

**Unknown-resolution schedule → exponential backoff from 1 s, doubling with
jitter, capped at 60 s; escalate to `PENDING_RESOLUTION` after 15 minutes or 12
attempts, whichever comes first.** Aggressive early, because the most likely
cause of an unknown is that the ledger committed and the response was lost — a
prompt retry replays the answer. Fifteen minutes is longer than any plausible
pod restart or database failover and short enough that a human can still act
within the horizon of a customer standing at a counter. Values are configuration
with these as defaults; the *shape* is contract.

Core's worker and a retrying caller do not conflict: the caller's same-key retry
resolves to the existing saga and reads its state, while only the lease-holding
worker calls the ledger.

**Ops cases → a human may never declare the outcome. Only the ledger can.**
The available actions are *re-resolve now* and *escalate*, never "mark this as
posted". A case resolves when a retry of the derived key finally returns a
definitive answer, at which point it closes automatically. This is what keeps
the outcome protocol intact under human pressure — the moment an operator can
assert an outcome, the protocol's central guarantee becomes advisory. Cases are
exposed on a permission-gated ops surface and raise `ops.case_opened` so they can
page. There is no exception — not even a ledger restore, which is recovered by
mechanical replay rather than by anyone's judgement.

**Two kinds of reversal, and only one of them involves a human.** The word
covers two categorically different operations, and treating them as one is a
design error in both directions — it either puts a human in the path of routine
self-healing, or it lets a machine undo a transaction on its own judgement.

- **Compensating reversal — automated, always.** A saga undoing a posting *it
  made itself* because a later step returned `DEFINITE_FAILURE`. No approval, no
  human, no queue. Requiring one would mean a partner outage generating thousands
  of pending approvals while customers' funds sat immobilized.
- **Business reversal — human, maker-checker, always.** Somebody decides a
  transaction the system considers correct should be undone: teller error,
  customer dispute, fraud.

**The separating rule: maker-checker gates judgement, not mechanism.** An
automated compensation has *zero discretion* — same saga, same accounts, same
amount, undoing exactly what it just did because the reason for doing it
evaporated. A business reversal is a human choosing an arbitrary past
transaction. Discretion is what needs a second pair of eyes.

Three constraints keep the automated path from becoming a way to move money
without authorization. A compensating reversal:

1. may target **only a posting made by the same saga** — never a transaction
   chosen by a query, a human, or another saga;
2. requires `DEFINITE_FAILURE` and is **forbidden on `UNKNOWN`** — the outcome
   protocol's central rule, restated where it is most tempting to break;
3. occurs within the saga's own lifetime, not days later.

Attribution reflects the difference: an automated compensation posts with
`initiated_by = system:core-saga-compensator`, which the ledger's schema already
anticipates ("the human principal **or system job**"). It never borrows a
person's identity.

**A compensation surge is a signal, not routine.** Automated compensations trip
a circuit breaker and alert above a rate threshold — a partner failing en masse
should stop the flow and wake someone, not quietly reverse ten thousand
transactions. They also appear in the ledger's authorized-exposure report, which
is where expected-but-notable money movement belongs.

**v1 has no automated compensation**, because a single-step saga that fails has
nothing posted to undo. It arrives with the first rails connector, alongside
multi-step sagas. v1 is nonetheless fully self-healing: a definite failure
releases the reservation and fails the saga with no human involved.

**Business-reversal authority → the approval object lives in `orchestration`;
Identity defines who may make and who may check.** PRD §4.10 draws exactly this line —
definitions centralized, workflow enforced by the owning service. An approval is
**bound to its target transaction and amount and is single-use**, so it cannot
be reused for a second reversal, and it records both identities with maker ≠
checker enforced. The reversal saga refuses to call the ledger without one.

**Tills → referenced by ledger account id, with a till registry in
`orchestration` and a validated branch.** A till is a ledger account
(PRD §4.7.4) and is not a customer, so it does not belong in `customer`. It is
genuinely a Branch concern — and since ADR 0012 there is one: a till is created
against an active `BRANCH` unit resolved through the `OrganizationUnits` port,
refusing `UNIT_NOT_FOUND` otherwise, and records the unit's id beside its code.
The table stays in `orchestration` because the money path reads it; whether it
*moves* into a teller-application service is still decided when that service
starts, and moving a validated table is cheaper than moving a free-text one.

**Limit windows → calendar day in the tenant's business timezone.** Regulatory
tier limits are expressed as daily limits meaning calendar days; customers
understand a limit that resets at midnight and file support tickets about
rolling ones; the ledger already establishes tenant business timezone as the
business-date authority; and a calendar window is a single key
(`daily:2026-08-05`) where a rolling window needs a time-series query on every
check.

**Reservations resolve only with their saga — they are never swept
independently.** The sweep operates on sagas; a reservation is mutated solely by
the worker holding that saga's lease, which makes the race with an active claim
structurally impossible rather than carefully avoided. `expires_at` is a backstop
against orphans, not the primary mechanism.

**A saga in `PENDING_RESOLUTION` keeps its reservation, deliberately.** The
money may have moved. Releasing the headroom would let a second transfer through
that, combined with a first which may have committed, breaches the customer's
limit — turning an unknown outcome into a certain compliance breach.

**v0 product configuration → FLAT and PERCENT fees (integer basis points) per
operation, PER_TXN and DAILY limits per tier and channel, versioned with
`effective_from`.** Deferred: tiered and banded fees, per-period caps, and
interest accrual entirely — accrual needs a scheduler and interacts with the
ledger's value dating, which is a separate body of work that nothing in v1
exercises. The deferral is made safe by the seam: `ProductDecisionService`
returns a decision object rich enough that adding rule kinds changes the
evaluator, never the call sites.

**Post-restore recovery → mechanical replay, not human judgement.** An earlier
draft treated a ledger restore as the one case where a human must decide, on the
reasoning that a rewound idempotency registry makes replay *create* a posting
rather than confirm one. That reasoning is wrong in a way worth recording: if the
posting survived the restore its key is still registered and replay returns the
original; if it did not survive, replay re-posts it, which restores exactly the
state the restore destroyed. Both branches converge, which is why the ledger's
own restore protocol says Orchestration replays its outstanding window and calls
it safe. The procedure is in [`saga-protocol.md`](saga-protocol.md).

**In v1 that reconciliation is operator-triggered, because Core cannot detect a
restore.** The ledger stamps its epoch on published events only, never on API
responses, and Core consumes no events in v1. The `epoch_at_post` column an
earlier draft carried was therefore unfillable and has been removed — a column no
writer can populate reads as a capability the service does not have. Automatic
detection arrives with event consumption, or sooner if the ledger exposes its
epoch on responses; that would be a ledger amendment and is not assumed here.

**Branch / organizational units → the `organization` module (ADR 0012).** The
deferral this section used to record has been resolved, ahead of the teller
application and deliberately narrower than the domain it will eventually serve:
`organization` owns the tenant's operational tree (typed, coded units whose
codes never recycle) and the attributed record of who is assigned where — and
nothing else. A unit is operational scope only; the ADR names what it must
never absorb (legal entity ≙ tenant, booking unit ≙ deferred ledger concern,
jurisdiction ≙ country pack). `tills` stays in `orchestration` because the
money path needs it, but its branch is now validated through the
`OrganizationUnits` port at creation and the unit's id recorded beside the
code; approvals snapshot the maker's and checker's unit scope as audit
attribution. No money-path decision branches on a unit — that restraint is the
design: the operational tree can be reorganized without a migration touching
money. Vault movements and teller assignment still arrive with the teller
application, on a Branch that now exists.

**Lending is out of scope for this build.** It was designed and built as a fifth
module above Orchestration (ADR 0013) and has been withdrawn to keep the core —
taking money in and out, knowing whose it is, and setting the institution up to
do both — finished before anything is stacked on it. The dependency order it
amended is back to its original form: nothing asks Orchestration but the app.

**The publisher adapters are duplicated with the ledger's, deliberately, for
now.** Both services now carry Kafka and logging publishers, so the `libs/`
extraction bar is met on paper. It is not done yet because the two abstractions
genuinely differ: the ledger publishes a *batch* and returns the ids the broker
acknowledged, so an unacknowledged event stays pending; Core publishes one event
and throws. Unifying them changes a documented relay contract in two AGREED
designs and deserves its own PR with an amendment on each side, rather than
being folded into unrelated work. The trigger is a third publisher, or the first
time the two drift apart in behaviour.

## Open questions

None blocking. The two carried in v0.2 are resolved above — one by finding the
original reasoning wrong, one by deferring with a placement and a trigger rather
than leaving it unanswered.

New questions land here and are resolved by amendment in
[`CHANGELOG.md`](CHANGELOG.md).

## Reopening a decision

Any decision above may be challenged with a concrete scenario the current design
handles wrongly — that is what "scrutinized in the open" means, and a decision
that cannot survive a counterexample deserves to fall. One already has: the
post-restore human-decision path was removed during drafting once the reasoning
behind it turned out to be wrong.

Now that this design is AGREED, a successful challenge lands as an amendment in
[`CHANGELOG.md`](CHANGELOG.md), and the superseded decision above is annotated
rather than deleted.
