# Product — Testing

**Status:** AGREED v1.0 (2026-08-13) — amendments via [`CHANGELOG.md`](CHANGELOG.md).

**18 tests green** against real PostgreSQL. Never an in-memory substitute: the
trigger, constraint and row-level-security behaviour under test *is*
PostgreSQL's, and a substitute would agree with us about everything except the
things worth testing.

The database is supplied rather than started by the run — `docker compose up -d
postgres` locally, a service container in CI — for the reason recorded in the
ledger's suite: Testcontainers needs the Docker *Engine* socket, and Docker
Desktop only exposes it when a non-default setting is on. With it off the CLI
works while every JVM client fails, which is a confusing failure to inflict on a
contributor.

## Suites

| Suite | Status | Proves |
|---|---|---|
| `PricingAuthoringApiTest` (7) | **IMPLEMENTED** | The authoring surface over real HTTP. Every case is a defect that survived its first audit because nothing exercised these routes: honest draft attribution, shape validation, backdating, publish locking and revalidation, concurrent-draft races answering 409 |
| `ProductServiceTest` (5) | **IMPLEMENTED** | What this service owns alone — the catalogue and its by-code lookup, a decision refusing an unknown product as an answer rather than an error, the decision taking its tenant from the token and not the body, an unknown tenant answering 404, and deny-by-default on the surface |
| `BoundaryTest` (6) | **IMPLEMENTED** | The boundaries this deployable claims: no client onto the money path, no sibling deployable imported, no `BigDecimal`, no legacy date API, internals stay internal. Includes the empty-import canary |

## The two assertions the extraction rests on

**Immutability is the load-bearing one.**
[ADR 0020](../../../docs/adr/0020-customer-and-product-become-deployables.md)'s
argument for extracting Product first is that a published version cannot change,
so a decision is a pure function of frozen configuration — safe to answer across
a network and safe to cache by version. That property is a database trigger, not
a convention, and a suite that did not exercise it would leave the argument
resting on a claim nobody checks.

**The tenant gate is the other.** Row-level security isolates tenants from one
another and says nothing about whether a tenant exists. Before the registry, any
UUID in a validated token was a working institution with an empty catalogue.

## Known gaps

| Gap | Status | Why |
|---|---|---|
| Error-catalog test | **PLANNED** | The scaffold asks every service for one — a build failure when a code exists without documentation, or is documented without existing. Core and the ledger have theirs; this service does not, so the twelve codes in [`api.md`](api.md) are checked by reading |
| Decision cache and its invalidation | **DEFERRED** | The cache is not built (see [`design.md`](design.md)). When it lands it needs a test that publishing a new version changes the key |
| Metrics | **PLANNED** | Prometheus is exposed and almost nothing feeds it. No decision latency, no refusal-rate-by-reason |
| Concurrency on publish | **PARTIAL** | `PricingAuthoringApiTest` covers concurrent drafting. Two publishers racing the same version relies on the trigger and is not directly exercised |
| Ledger-unavailable path | **PARTIAL** | `LEDGER_UNREACHABLE` is coded and returned; there is no test that stops the ledger and asserts a fee rule is refused rather than stored unverified |
| Performance | **DEFERRED** | No evidence. The decision is on the money path and its latency is a stated cost in ADR 0020, unmeasured |
