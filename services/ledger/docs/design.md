# Ledger — Design Index & Decision Log

**Status:** AGREED v1.11 (2026-08-08) — implemented; see CHANGELOG for amendments.
Amendments follow [`CHANGELOG.md`](CHANGELOG.md) and the
[design-change convention](../../../docs/conventions/design-changes.md).
**Source:** platform PRD §4.1 (ledger), §3 (constitution), §5 (communication
map), §7 (NFRs), §8 (testing), Appendices A/B.

## The design, by topic

| Topic | Doc |
|---|---|
| Tables, relationships, schema-encoded rules, decided edge cases | [`data-model.md`](data-model.md) |
| Boundaries, traffic, outbox/relay contract, DR posture | [`architecture.md`](architecture.md) |
| Endpoint surface, error catalog, contract properties | [`api.md`](api.md) |
| Posting/reversal/hold execution, concurrency, hot accounts | [`posting-algorithm.md`](posting-algorithm.md) |
| Invariants, exposure split, all test suites | [`testing.md`](testing.md) |
| Amendments since this design was agreed | [`CHANGELOG.md`](CHANGELOG.md) |

One-paragraph summary: a multi-tenant double-entry posting engine on
PostgreSQL. Transactions of 2..N entries (N per-tenant, platform maximum 100),
balanced per currency, committed atomically; entries append-only and account
identity immutable (trigger-enforced); balances derived and provable; holds
with mandatory TTL and atomic capture; idempotency (with payload fingerprints)
on every creating operation, arbitrated by unique constraints; events via a
precisely-specified transactional outbox; **no synchronous outbound calls and
no events consumed, ever**; one writer (Orchestration); RPO = 0 for
acknowledged commits.

## Decision log

Format: decision · resolution · rationale.

**Balance sign convention → credit-positive (liability-normal)** with
`allow_negative` for internal/cash-style accounts. One uniform rule beats
type-aware normal balances in code clarity and teachability.

**Hold capture → atomic `consumeHoldId` on posting; capture-as-saga
rejected.** Between a saga's release and post, concurrent spending can consume
the funds while the bank has already settled externally — an unclosable race
outside the DB transaction.

**Capture is single-shot.** A capture below the hold amount consumes the hold
and *explicitly* releases the remainder, both amounts on the event. Multi-shot
capture is not in v1; orchestration places a new hold if it needs another
reservation.

**Statement read model → a period document, not a feed.** Modelled on ISO
20022 `camt.053` and SWIFT MT940: bounded period, opening and closing balances
that reconcile, both booking and value dates on every line, and a final/interim
split (camt.053 vs camt.052) that falls out of accounting-period close. A
statement over a closed period is immutable by construction, so no pinned
cursor or snapshot machinery is needed. Statements are explicitly *not* a
change feed — entry ids are assigned at insert rather than commit, so a durable
cursor over them silently skips late-committing entries; the outbox is the
change feed. The analytical store arrives with Compliance/Reporting; no early
build.

**Two dates per entry → `booked_at` and `value_date`.** Both statement
standards mandate both, so one date would make compliant export impossible; it
also gives as-of reporting a natural predicate and makes backdated items
legible to customers.

**Event payloads → thin (ids + minimal summary).** Consumers must be
state-based regardless (the relay guarantees per-batch ordering only), so fat
payloads would add PII surface for no benefit.

**Tenant provisioning → versioned seed script pre-Identity-service.** A stub
admin endpoint would become an untracked production surface; a script is
reviewable and disposable.

**Value dating → bounded and governed.** No future dates; tenant backdate
window (default ≤ 30 days) with mandatory reason; accounting-period close
rejects postings into closed periods; business date = tenant-timezone date.

**Hot-account contention → fan-in sub-accounts, restricted to unguarded
accounts.** Sharding an `allow_negative = false` account would break the
negative-balance guard (per-row, so a funded group could still reject a debit
on a short shard); restricting fan-in to unguarded internal accounts is what
makes it invariant-neutral. Deployment is gated on an objective CI throughput
floor, not on judgement.

**Concurrency → two-tier global lock protocol.** Tier 1 = target transaction
rows (by id), tier 2 = balance rows (by account id); no tier-1 acquisition
while holding tier 2. A single sorted lock class is not sufficient once
reversal and compensation both need the original transaction's row.

**Transaction isolation → READ COMMITTED.** The design leans on `SELECT …
FOR UPDATE` re-reading the latest committed row after the lock is granted, and
on unique-index insert conflicts as the idempotency arbiter. Both are READ
COMMITTED idioms; under REPEATABLE READ the same paths raise serialization
failures instead of returning the answers the algorithm depends on.

**Idempotency fingerprint → hashed over the request as received.** Omitted
optional fields canonicalize as absent, never as server-resolved defaults —
otherwise a mandated same-key retry that crosses the tenant's midnight would
resolve a different default `valueDate`, produce a different fingerprint, and
be rejected as key reuse.

**Monetary serialization → decimal strings in responses and event payloads.**
Entry amounts are capped below 2^53, but balances and group sums are uncapped
aggregates; one uniform rule means no JSON consumer ever silently loses
precision.

**Persistence → plain SQL over JDBC; no ORM in this service.** The posting
algorithm is written in terms of things an ORM takes away: `SELECT … FOR UPDATE`
in a stated lock order, `ON CONFLICT` as the idempotency arbiter, and
`set_config(..., true)` for the tenant session. An ORM decides statement order
at flush time, which is incompatible with a deadlock argument that rests on
acquiring balance rows in sorted account order; its identity map can serve a
pre-lock copy of a row that was just locked; its idiom is optimistic versioning
where this design deliberately chose pessimistic locks to avoid retry storms on
hot accounts; and its dirty-checking fights append-only triggers rather than
cooperating with them. There is also an audit argument: a reader can follow
`PostingService` and see every statement that moves money.

This is a decision about *this* service, not a platform rule. CRUD-shaped
services elsewhere may reasonably choose JPA; nothing in `AGENTS.md` forbids it.

**Schema migrations → append-only Flyway with schema-presence tests.** The
schema's correctness lives in triggers, partial unique indexes, composite FKs,
and RLS policies — objects a careless migration can silently drop.

**Tenant context → `SET LOCAL` inside the request transaction.** Connections
are pooled and reused across tenants, so a session-scoped `SET` would return to
the pool still carrying the previous tenant's identity. RLS exists to catch
queries the application forgot to scope; a leaked session variable would make
it fail in precisely that case.

**Group membership → a label, scoped by RLS rather than by a foreign key.**
`group_ref` is the single identifier not covered by a composite FK. Members are
resolved through `accounts`, which RLS already confines to the calling tenant,
so cross-tenant groups cannot be assembled. Stated explicitly, and tested,
because it is the one exception to an otherwise structural rule.

**Currency exponent → immutable once referenced.** `minor_unit_exponent` is the
only value that reinterprets every stored amount in every tenant at once;
changing it would turn ₦1,000.00 into ₦100,000.00 with no entry modified and
every invariant still green. Redenomination is a new currency code and a
migration, never an `UPDATE`.

## Reopening a decision

Any decision may be challenged — by issue or PR against these docs, with a
scenario the current design handles wrongly. That is what "scrutinized in the
open" means; a decision that can't survive a concrete counterexample deserves
to fall. Now that this design is AGREED, a successful challenge lands as an
amendment in [`CHANGELOG.md`](CHANGELOG.md), and the superseded decision above
is marked rather than deleted.
