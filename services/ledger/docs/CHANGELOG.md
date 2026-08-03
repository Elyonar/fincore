# Ledger — Design Changelog

Amendments to the **agreed** ledger design. Process and entry format:
[`docs/conventions/design-changes.md`](../../../docs/conventions/design-changes.md).

The version here matches the status header in every ledger design doc. Newest
entry first.

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
