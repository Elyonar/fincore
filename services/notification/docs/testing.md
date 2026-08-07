# Notification — Invariants & Test Strategy

**Status:** AGREED v1.2 (2026-08-06) — amendments via [`CHANGELOG.md`](CHANGELOG.md)

Every suite below carries a status marker, and **only IMPLEMENTED suites gate
merges**. Moving a marker requires the tests to exist. A design describing
verification that does not run is worse than an admitted gap: a reviewer or an
agent reads it as fact.

Hard rule 7's money-path merge gate does **not** attach here. This service moves
no money, and saying so explicitly is better than leaving a reader to infer it.

## Invariants

Checked per tenant, after every test scenario and on a schedule in production.

1. **Every consumed event reaches exactly one terminal disposition** — a message,
   or a suppression carrying a reason code. No silent drops, ever. This is the
   one that defines the service.
2. **At most one successful delivery** per `(tenant, business_moment_key,
   category, channel, recipient)`.
3. **No `(publisher, event_id)` is processed twice.**
4. **Every `SENT` notification records the template version that produced it.**
5. **No message is sent from an event older than `max-event-age`**, nor from a
   fenced epoch.
6. **Terminal states are terminal**, trigger-enforced.
7. **No recipient address appears in any log**, and none survives its retention
   window.
8. **No message exceeds its channel's `max_units`**, and every message records
   the unit count it was billed at.
9. **Every enabled channel has exactly one sender**, checked at startup. A
   channel with none queues forever; a channel with two sends twice.

## Suites

| Suite | Proves | Status |
|---|---|---|
| **Schema** (`SchemaTest`, 12) | Every table, trigger, constraint and policy exists **and fires**: required parts, published immutability, attribution and measurement, complete quiet-hour windows, the dedupe key, one message per moment, terminal states, append-only attempts, tenant isolation | **IMPLEMENTED** |
| **Intake** (`EventIntakeTest`, 15) | Both parties notified from one transfer; redelivery handled once; two events about one moment producing one message each; epoch fencing; the staleness guard; unmapped events recorded as `IGNORED`; address encrypted at rest with the template version recorded; and every suppression reason reachable on the intake path — `NO_ADDRESS`, `OPTED_OUT`, `NO_TEMPLATE`, `MISSING_VARIABLE`, `NO_POLICY`, `UNKNOWN_ACCOUNT` — plus the transactional quiet-hours exemption and channel fallback | **IMPLEMENTED** |
| **Send queue** (`SendWorkerTest`, 9) | Claimed once and attempted with history; the address decrypted only at the sender and the client reference derived, never random; a definite rejection terminal; an unknown retried with backoff; a sender that *throws* treated as unknown rather than failure; attempts counted at claim so a crash mid-send buys no free retry; a lease keeping a second instance off one message; exhaustion failing the message and recording `ATTEMPTS_EXHAUSTED` | **IMPLEMENTED** |
| **API** (`ApiTest`, 10) | Draft → publish with attribution and measurement; a channel's required parts enforced at create; unknown channel refused; double publish a 409; policy round trip; half a quiet window refused; reads exposing no address; **every endpoint denying by default**; and the bidirectional document check | **IMPLEMENTED** |
| Replay safety | A topic redriven from `earliest` produces zero sends — the offset half; the age half is covered by the intake suite | PARTIAL |
| Rendering | Determinism, and GSM-7 vs UCS-2 segment counts on Hausa, Yoruba and Igbo samples. Missing-variable suppression and **locale selection with fallback** are covered by the intake suite | PARTIAL |
| Channel registry | An enabled channel with no sender fails startup. A channel added by row alone reaching send is covered indirectly by the intake and API suites, not directly | PARTIAL |
| Failure injection | Gateway timeout and a throwing sender are covered by the send-queue suite. Core unreachable leaving the event unconsumed, and an absent adapter, are not | PARTIAL |
| ArchUnit + empty-import canary | No ledger client, no gateway SDK, `internal` stays private — and the suite is not passing vacuously | PLANNED |
| Error catalog | `ErrorCodeCatalogTest` copy: no undocumented code, no documented non-code | PLANNED |

Real PostgreSQL throughout, never an in-memory substitute — the trigger,
constraint and row-level-security behaviour under test is PostgreSQL's.

## What the implemented suite has already caught

Recorded because a test's value is best argued by what it found, not by its
presence in a table.

- **Every "app" query was running as the database owner.** The owner
  `DataSource` bean is `@Primary` and is a `HikariDataSource` at runtime, so
  unqualified by-type injection resolved to *it* rather than to the restricted
  role — and the owner is a superuser, which PostgreSQL exempts from row-level
  security entirely. The service would have run every statement as the one
  identity no policy applies to, while the catalog reported every policy
  enabled. The tenant-isolation test failed; nothing else would have.
- **Two constructors made the service unstartable**, and only a context-loading test says so. A
  test-visible constructor for injecting a fixed `Clock` left the container unable to choose, and
  the failure surfaced as every test in the module erroring at once rather than as anything about
  constructors.
- **A grant made the tidy fixture impossible, which is the grant working.** The intake suite wanted
  to express "no template" by deleting one; the app role has no `DELETE` on `templates`, because a
  published template is evidence. The fixture now expresses it by never publishing one — closer to
  what actually happens in production, and it was the schema that insisted.
- **An endpoint shipped with no permission check.** `GET /v1/templates` authorised nobody and
  answered everybody, for exactly as long as it took a deny-by-default test to ask. Review had read
  the file twice.
- **Responses were leaking column names as the API contract.** The template endpoints returned row
  maps, so `template_key` and `published_by` were the published shape — renaming a column would have
  been a breaking change for every caller. It is the mistake ADR 0008 forbids in event payloads,
  arriving through a convenience method instead.
- **The migration ordering was masked by a database that had already been migrated.** Moving the
  suite to `notification_test` — following the ledger and Core — meant an empty database on the
  first run, and the channel registry read its table before Flyway created it. The fix is the
  ordering the service always needed; the dedicated database is what made the gap visible.
- **Append-only was proven by the wrong mechanism.** The first version of the
  attempts test used the app role, which has no `UPDATE` grant — so it passed
  while saying nothing about the trigger. Grants and triggers protect different
  callers, and the owner is the one only the trigger protects. The test now
  asserts both.

## Deferred, and why

- **Load and soak.** No throughput target has been agreed, and a benchmark
  without an agreed reference machine measures the laptop.
- **A real gateway.** Delivery receipts, bounce handling and monetary cost
  cannot be tested against an adapter that delivers nothing. They arrive with
  the messaging connector, and the suites for them arrive with it.
