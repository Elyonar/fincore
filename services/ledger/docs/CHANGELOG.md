# Ledger — Design Changelog

Amendments to the **agreed** ledger design. Process and entry format:
[`docs/conventions/design-changes.md`](../../../docs/conventions/design-changes.md).

The version here matches the status header in every ledger design doc. Newest
entry first.

---

## [1.3.0] — 2026-08-05 · MINOR

**Statement lines are paged; `GET /v1/transactions/{id}` is confirmed part of
the contract.**

- **Docs:** `api.md`
- **Why:** the statement contract specified ordering, reconciliation and the
  final/interim split, but never response *size*. At 200 TPS a busy account's
  yearly statement is an unbounded query serialized into one response on the
  operational primary — fine in testing, and exactly the shape of thing that
  fails first on the largest customer. The section also said the endpoint
  "needs no pinned cursor", which reads as forbidding pagination when it was
  only ever forbidding a durable change-feed cursor; the two are now
  distinguished explicitly, because someone will otherwise fix one by breaking
  the other.
- **Impact:** a caller that assumed a single complete response must now follow
  `nextCursor`. Recorded as MINOR rather than MAJOR because the contract never
  promised an unbounded response, and no consumer exists yet; the behavioural
  change is stated here so it cannot be discovered by surprise.
- **Supersedes:** nothing. Ordering, reconciliation and the final/interim split
  are unchanged, and `opening`/`closing` still describe the whole period.
- **Tests:** statement paging across page boundaries, reconciliation across all
  pages, the page-size cap, and `GET /v1/transactions/{id}` returning a
  transaction with its entries.
- **Migration:** none.

*`GET /v1/transactions/{id}` was in the agreed endpoint table from v1.0 but was
never implemented — found by diffing `api.md` against the controllers rather
than by a failing test, which is a gap in the suite as much as in the code.*

---

## [1.2.0] — 2026-08-05 · MINOR

**Two verification tables added: `balance_anchors` and `invariant_runs`.**

- **Docs:** `data-model.md` (nine tables → eleven)
- **Why:** incremental invariant verification needs somewhere to keep its
  proven checkpoints and its run history. The design described both mechanisms
  in `testing.md` but never gave them a home in the data model, so the schema
  and the documented table count had diverged the moment they were built.
- **Impact:** additive. No existing table, constraint or contract changes; no
  caller is affected.
- **Supersedes:** nothing.
- **Tests:** `SchemaPresenceTest` table count and presence checks;
  `QuiesceHorizonTest` proves the bound never advances past an in-flight
  writer, which is the property the anchors rest on.
- **Migration:** `V5__invariants.sql`

*Found by the guardrail rather than by review: the schema-presence suite
asserts the documented table count, so adding a table without amending the
design failed the build.*

---

## [1.1.1] — 2026-08-05 · PATCH

**Persistence approach recorded: plain SQL over JDBC, no ORM.**

- **Docs:** `design.md`
- **Why:** the choice was visible only in a `pom.xml` comment and a
  `package-info.java`, so it was absent from the place a contributor looks for
  rationale and would be re-litigated on every review. Nothing about the
  contract changes; this states a constraint that was already true and already
  implemented.
- **Impact:** clarification, no behavioural change. Scoped to the ledger — other
  services may reasonably choose an ORM.
- **Supersedes:** nothing.
- **Tests:** none required; no behaviour changes. The constraint is already
  observable in `PostingService`, and the ArchUnit suite continues to enforce
  the rules that motivated it.

---

## [1.1.0] — 2026-08-05 · MINOR

**Row-level security requires a constrained role and FORCE; both are now stated.**

- **Docs:** `architecture.md`
- **Why:** the v1.0 design described RLS as the tenant-isolation backstop and
  specified the `SET LOCAL` context discipline, but said nothing about the
  privileges of the connecting role. Implementation showed that omission was
  fatal rather than incidental: PostgreSQL exempts a table's owner from its own
  policies unless the table is `FORCE`d, and exempts a SUPERUSER or `BYPASSRLS`
  role unconditionally. With the service connecting as the bootstrap superuser,
  every policy was inert while `pg_class.relrowsecurity` still reported enabled
  — a guarantee that failed silently and survived inspection.
- **Impact:** backward compatible as a contract; operationally required.
  Deployments must provision `ledger_app` (NOSUPERUSER, NOBYPASSRLS, DML only)
  and run migrations as the owner.
- **Supersedes:** nothing. This closes a gap rather than reversing a decision;
  the `SET LOCAL` rule from v1.0 stands unchanged and is now stated as the
  *second* of two requirements rather than the only one.
- **Tests:** `TenantIsolationTest` (rows hidden across tenants, no-context reads
  return nothing, cross-tenant insert refused, shared `group_ref` does not sum,
  pooled connections do not inherit context); `SchemaPresenceTest` guards that
  RLS is FORCEd and that the connecting role is neither superuser nor
  BYPASSRLS.
- **Migration:** `V2__force_row_level_security.sql`, `V3__application_role.sql`

---

## [1.0.0] — 2026-08-04 · AGREED baseline

**Initial agreed design.** Implementation may begin.

- **Docs:** [`design.md`](design.md), [`data-model.md`](data-model.md),
  [`architecture.md`](architecture.md), [`api.md`](api.md),
  [`posting-algorithm.md`](posting-algorithm.md), [`testing.md`](testing.md)
- **Scope:** a multi-tenant double-entry posting engine on PostgreSQL — nine
  tables, one writer (Orchestration), no synchronous outbound calls, no events
  consumed, RPO = 0 for acknowledged commits.
- **Correctness lives in the schema:** append-only entries, immutable account
  identity, immutable currency exponent, composite `(tenant_id, id)` foreign
  keys, RLS backstop with `SET LOCAL` tenant context, bounded amounts, and
  idempotency keys on every creating operation.
- **Concurrency:** a two-tier global lock protocol — target transaction rows
  by id, then balance rows by account id — under READ COMMITTED.
- **Guarantees:** six invariants, with the violation vs authorized-exposure
  split that keeps the alarm meaningful, verified incrementally against daily
  physical anchors captured at an MVCC quiesce horizon.
- **Statements** follow ISO 20022 `camt.053` / SWIFT MT940: bounded period,
  opening and closing balances that reconcile, booking *and* value dates per
  line, and a final/interim split that falls out of accounting-period close.
- **Tests:** every suite in [`testing.md`](testing.md) gates merges and runs
  against real PostgreSQL — never an in-memory substitute.

**Known follow-ups**, tracked but not blocking implementation:

1. The MVCC quiesce horizon is built and proven standalone before anchors or
   the outbox relay depend on it.
2. CI gains a PostgreSQL service before the first schema commit — the current
   workflow has none, and every suite requires a real database.

---

<!--
Template for the next entry — copy, don't edit history above.

## [1.1.0] — YYYY-MM-DD · MINOR

**One-line summary.**

- **Docs:** which files changed
- **Why:** the driver, in a sentence or two
- **Impact:** BREAKING / backward-compatible / clarification — and who must act
- **Supersedes:** the decision in design.md this replaces, if any
- **Tests:** the suites or cases that prove it
- **Migration:** V<n>__<name>.sql, if the schema moves
-->
