# Customer — Testing

**Status:** AGREED v1.0 (2026-08-13) — amendments via [`CHANGELOG.md`](CHANGELOG.md).

**35 tests green** against real PostgreSQL. Never an in-memory substitute: the
trigger, constraint and row-level-security behaviour under test *is*
PostgreSQL's, and this schema holds the platform's only PII, so tenant isolation
here is the isolation that matters most — and it is a database policy rather than
application code. A substitute that did not enforce it would make the suite agree
with a guarantee nothing was actually keeping.

The database is supplied rather than started by the run — `docker compose up -d
postgres` locally, a service container in CI. The reason Testcontainers is not
used is recorded in the ledger's suite.

## Suites

| Suite | Status | Proves |
|---|---|---|
| `CustomerApiTest` (15) | **IMPLEMENTED** | Registration and the register: numbering drawn from the institution's series, `external_ref` collisions answering 409, tier changes requiring a reason and recording it, account linking, deny-by-default, and an unauthenticated caller getting 401 rather than 404 |
| `ContactAndConsentApiTest` (13) | **IMPLEMENTED** | The contact surface Notification depends on: addresses present and absent, consent per category and channel, the `by-account` lookup, and — the one that matters most — another tenant's request for the same account answering 404 |
| `BoundaryTest` (7) | **IMPLEMENTED** | The boundaries this deployable claims: no client onto the money path, no ledger client at all, no sibling deployable imported, no money type, no legacy date API, internals stay internal. Includes the empty-import canary |

## What the suites deliberately pin

**Cross-tenant reads are indistinguishable from absent ones.**
`ContactAndConsentApiTest` asserts that another tenant asking for a real account
gets the same `404` as anyone asking for a fictional one. That is a disclosure
control, and it is the assertion most likely to be "fixed" by someone making
error messages more helpful.

**Append-only history is exercised, not assumed.** The tier and consent triggers
are database behaviour, and a test that only checked the current-state projection
would pass while history was being rewritten underneath it.

## Known gaps

| Gap | Status | Why |
|---|---|---|
| Error-catalog test | **PLANNED** | The scaffold asks every service for one — a build failure when a code exists without documentation, or is documented without existing. Core and the ledger have theirs; this service does not, so the ten codes in [`api.md`](api.md) are checked by reading |
| Schema-presence suite | **PLANNED** | The scaffold asks for a test asserting each trigger, partial index and policy exists *and fires*. The ledger has one; this service relies on the API suites reaching the triggers indirectly |
| Contact update | **NOT APPLICABLE** | There is no endpoint to test — see [`design.md`](design.md). Its absence is the gap |
| PII encryption | **NOT BUILT** | Contact details are plaintext, so there is nothing to test. Recorded so the absence is visible rather than assumed |
| Metrics | **PLANNED** | Prometheus is exposed and almost nothing feeds it |
| Eligibility under load | **DEFERRED** | Both eligibility reads are on the money path and uncacheable by design (ADR 0020). Their latency is an accepted cost, unmeasured |
| Erasure / offboarding | **DEFERRED** | Not built, and the design question is open — see [`design.md`](design.md) |
