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
