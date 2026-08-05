# ADR 0004 — The Ledger Service is built first

**Status:** Accepted · **Date:** 2026-08-03

## Context

Twelve services are specified; one must come first, buildable independently by a
small team.

## Decision

The Ledger Service (`services/ledger`) is built first, standalone, to
production quality, with the full PRD §8 test strategy (invariant,
property-based, concurrency, failure-injection suites) green in CI before any
other service starts.

## Rationale

- **Zero dependencies:** it calls no other service and consumes no events; it
  only exposes an API and emits events (via a transactional outbox that
  predates any broker).
- **Everything depends on it:** Orchestration posts to it; Product configures
  what gets posted; channels feed it.
- **It is the trust artifact:** a public double-entry ledger whose test suite
  provably conserves money is the strongest possible credibility signal for an
  open-source banking project.
- **It is deliberately small and stable** ("small, boring, rarely changed"),
  so it can be finished — a completed first act, not a sprawling half-build.

## Consequences

- Design agreed in `services/ledger/docs/` before implementation.
- No fee logic, product rules, external calls, or orchestration may ever enter
  this module; reviews enforce it.

---

## Precondition status (2026-08-05)

This ADR gates the second service on the PRD §8 suites being green in CI. That
is now the case for the four it names:

| Suite | State |
|---|---|
| Invariant | Green — six invariants, violation/exposure split |
| Property-based | Green — jqwik, generated operation sequences with shrinking |
| Concurrency | Green — races, ABBA and reversal-vs-compensation lock-order proofs, zero deadlock aborts |
| Failure injection | Green — unacknowledged publish, relay crash, duplicate delivery, backend terminated mid-transaction |

The precondition is **met**. Two things it did not require, and does not block on,
are recorded in `services/ledger/docs/testing.md` under "Deferred, and why": the
hot-account throughput benchmark (needs an agreed reference machine) and restore
drill automation (needs backup infrastructure). Neither is a test of correctness;
both are measurements of an environment that does not exist yet.

Stating this here rather than in a commit message, because a precondition that
is quietly assumed satisfied is the same as one that was never written.
