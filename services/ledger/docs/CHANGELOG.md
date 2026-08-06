# Ledger — Design Changelog

Amendments to the **agreed** ledger design. Process and entry format:
[`docs/conventions/design-changes.md`](../../../docs/conventions/design-changes.md).

The version here matches the status header in every ledger design doc. Newest
entry first.

---

## [1.8.0] — 2026-08-06 · MINOR

**The suite gets its own database.**

- **Docs:** `testing.md`, `README.md`
- **Why:** `ledger` was shared by the test suite and by `docker compose up`, and
  the running container writes to it. `HoldExpirySweep` fires every thirty
  seconds and appends `HOLD_RELEASED` events — precisely what `OutboxTest`
  counts — so a sweep landing between a test's drain and its assertion shifted
  the totals by however many holds happened to be swept. Intermittent, and
  indistinguishable from a real relay defect while it lasted. `AnchorServiceTest`
  had already failed the same way for a different reason: transaction ids are
  **cluster-wide**, so Core's workers polling their own database in the same
  PostgreSQL instance held this service's quiesce horizon down.
- **Impact:** none on the product. The suite defaults to `ledger_test`;
  `SPRING_DATASOURCE_URL` still overrides. Flyway builds it from the same
  migrations, so it cannot drift from `ledger`.
- **The deliberate exception:** `LedgerContractTest` still runs against the
  *running* Ledger and therefore the development database. Exercising a real
  deployable is the whole point of it, and pointing it at a schema that merely
  looks like one would remove the only thing it proves.
- **Supersedes:** the drain-first comments in `OutboxTest` as the mechanism for
  coexisting with other writers. They stay — they still handle leftovers from
  earlier tests in the same run — but they were never able to handle a writer
  that fires *after* the drain.
- **Tests:** 230 green with the stack running, and the development database's
  `outbox_events` count identical before and after a full run.
- **Migration:** `db/init/50-test-databases.sql`, which runs only on an empty
  volume; `README.md` carries the one-line command for an existing checkout.

---

## [1.7.0] — 2026-08-06 · MINOR

**The published envelope becomes ADR 0008's envelope. `V7__outbox_envelope.sql`.**

- **Docs:** `architecture.md`, `data-model.md`
- **Why:** ADR 0008 specifies one envelope for the whole platform, and the wire
  did not carry it. The ledger published the payload column verbatim: the
  restore generation was a payload field named `ledgerEpoch`, the tenant and
  aggregate were duplicated inside the body, `occurredAt` was absent, and **the
  outbox row id — the deduplication key the ADR mandates — never left the
  process at all.** Core meanwhile published a nested envelope with the epoch
  named `epoch`. Two publishers, two shapes, and the ledger's own contract
  ("consumers deduplicate on outbox id") unsatisfiable by any consumer. Nothing
  consumes events yet, which is exactly why nothing caught it — and exactly why
  the cost of fixing it is zero today and a breaking change for every consumer
  later.
- **Impact:** **BREAKING for anything parsing published events — and nothing
  does.** The message body is now
  `{eventId, eventType, aggregateId, tenantId, occurredAt, epoch, payload}`,
  with the domain payload nested under `payload`. `ledgerEpoch`, and the
  duplicated `tenantId` / `aggregateId`, are gone from the payload: they are
  envelope fields now, and one fact in two places eventually becomes two
  answers. ADR 0008's rule that a removed field needs a new `eventType` exists
  to protect consumers; with zero consumers the honest move is to fix the shape
  now rather than carry a duplicate field permanently, and this entry is that
  decision being stated rather than assumed.
- **Supersedes:** "every payload carries `ledgerEpoch`" in the v1.5 outbox
  contract. The restore protocol is unchanged in substance — the epoch is
  stamped at write time and travels with the event; only its home moved from the
  payload body to the row and then to the envelope.
- **Tests:** `PlatformEnvelopeTest` and `PublishersSendTheEnvelopeTest` in
  `libs/events` — the seven fields in the ADR's order, payload embedded as JSON
  rather than a re-parsed string, a partial envelope rejected at construction,
  and the assertion that a publisher puts `envelope()` on the wire and not
  `payload()`. That last one is the regression that actually happened: a test on
  the renderer alone would have passed throughout. The existing 230-test suite
  is green, including invariants, property-based, concurrency and
  failure-injection.
- **Migration:** `V7__outbox_envelope.sql` — adds `epoch BIGINT NOT NULL
  DEFAULT 1 CHECK (epoch > 0)` to `outbox_events`. The default is correct rather
  than convenient: the epoch starts at 1 and only a restore advances it, so
  every pre-existing row was written under epoch 1.

*The envelope is now rendered once, in `libs/events`, for every publisher. A
shared instruction to build the same JSON is a convention; a shared renderer is
a guarantee, and the difference only shows up when there are consumers to
break.*

---

## [1.6.0] — 2026-08-06 · MINOR

**The publisher adapters move to `libs/events`, shared with Core.**

- **Docs:** `architecture.md`
- **Why:** Core grew its own Kafka and logging publishers, so the platform had
  two implementations of one idea. `AGENTS.md` extracts a library when a second
  consumer exists, and one did. Keeping both would have let them drift in the
  behaviour that matters most — what counts as an acknowledgement.
- **Impact:** **operators must act.** The broker key is now
  `fincore.events.broker`, not `ledger.events.broker`; likewise
  `fincore.events.topic-prefix` and `fincore.events.exchange`. One key across the
  platform, so a deployment cannot half-migrate by changing it in one service and
  forgetting the other. `compose.yaml` is updated.
- **Supersedes:** nothing in the relay contract. Poll semantics, at-least-once,
  per-aggregate ordering and consumer-side dedupe on outbox id are unchanged —
  the ledger's batch-with-acknowledgements shape is the one the library adopted,
  because a batch where the third send fails must still mark the first two
  published, and a single-event signature cannot express that.
- **Tests:** `PublisherSelectionTest` (all three adapters, now resolved from the
  library), `FailureInjectionTest`, and the ledger's own `HardRulesTest`, whose
  cross-service rule was written before any library existed and now states that a
  shared library is not another deployable (PRD §3.4).
- **Migration:** none to the database.

## [1.5.0] — 2026-08-06 · MINOR

**An error contract a non-anglophone caller can render from.**

- **Docs:** `api.md` (error catalog rewritten, reasons table added)
- **Convention:** [`docs/conventions/error-contract.md`](../../../docs/conventions/error-contract.md) — new, platform-wide
- **Why:** the catalog's own `LIMIT_EXCEEDED` row read "entries > cap, amount >
  cap, key > 200 chars" — the doc admitted the code was overloaded. In the
  source it covered eight distinct failures, and `ACCOUNT_NOT_FOUND` covered
  four. The only thing separating them was an English sentence in `message`.
  A francophone tenant's channel could therefore say no more than "invalid
  request", because everything specific was in prose it must not parse. The
  ledger knows what is wrong; only the channel knows the language, and nothing
  in the contract carried the first across to the second.
- **Change:** the error body gains `reason` (sub-classification where one code
  spans several causes) and `details` (machine-readable parameters — a field
  name, a limit, what was supplied). `message` is now explicitly developer
  English: never displayed, never parsed, reworded without an amendment.
- **Impact:** backward compatible. `code`, `retryableWithSameKey` and `detail`
  are unchanged; the new members are omitted from the wire when empty, so a
  rejection carrying no parameters looks exactly as it did in v1.4. Existing
  callers keying on `code` alone keep working — they simply cannot localize.
- **Tests:** `ErrorCodeCatalogTest` — fails the build when a code or reason
  exists in the source without appearing in `api.md`, and when `api.md`
  documents a code that no longer exists. The second direction matters as much
  as the first: a documented code that cannot occur means somebody writes a
  French message for a rejection that will never fire and trusts a table that
  is lying. Non-error vocabulary is derived from the status enums, so adding a
  status is never mistaken for an undocumented error.
- **Migration:** none — no schema change.

---

## [1.4.0] — 2026-08-05 · MINOR

**Tenant registry and ledger epoch — two things the design specified and the
implementation never had.**

- **Docs:** `data-model.md` (eleven tables → thirteen)
- **Why:** both were written as design and silently absent, which is worse than
  a gap the docs admit — they read as done.
  - `design.md` recorded "tenant provisioning → versioned seed script" and
    `data-model.md` said `tenant_config` is "seeded by the provisioning script".
    No script existed, and `TenantConfigService` falls back to platform defaults
    when no row matches, so **any UUID in the header was a valid tenant** with a
    working ledger of its own. Every isolation test passed, because isolation
    between tenants was never what was broken.
  - `architecture.md` specifies a restore protocol in which every published
    event carries a ledger epoch. The field did not exist anywhere — not in the
    schema, not in a payload. It is a *consumer-side* contract, so the first
    service to consume events would have been written against something absent.
- **Impact:** additive for callers. Operationally required: a tenant must now be
  provisioned before it can transact, and `db/init/20-dev-tenant.sql` seeds one
  for local development. Event payloads gain `ledgerEpoch`.
- **Decision recorded with it:** `tenants` is a **local projection**, not a join
  and not a subscription. The ledger makes no synchronous outbound calls, so it
  cannot ask Identity mid-posting whether a tenant is real without putting
  another service's availability in the money path. It keeps an id, a name and a
  status — nothing about the customer — and provisioning writes it, which keeps
  "events consumed: NONE, EVER" exactly true. A tenant feed would make accepting
  money depend on a message having arrived; a missed one would leave a real bank
  silently unable to post.
- **Supersedes:** nothing.
- **Tests:** `TenantRegistryTest` — unknown and suspended tenants refused, over
  HTTP as 404, and indistinguishable from any other not-found so the endpoint is
  not an enumeration oracle. Epoch presence asserted in the outbox suite.
- **Migration:** `V6__tenant_registry_and_epoch.sql`, which backfills `tenants`
  from existing accounts and config so the new foreign keys can be trusted
  rather than merely declared.

---

## [1.3.1] — 2026-08-05 · PATCH

**Test suites marked IMPLEMENTED / PARTIAL / PLANNED.**

- **Docs:** `testing.md`
- **Why:** the document asserted that every suite listed gates merges, while
  property-based tests, failure injection, migration equivalence, restore drills
  and the hot-account throughput benchmark did not exist at all. That is worse
  than the missing tests themselves: a specification a reviewer or an agent
  reads as fact, describing verification that never runs. An AI agent is
  especially exposed, because it treats a detailed authoritative document as
  established unless told to verify it.
- **Impact:** no behavioural change and no contract change. What altered is the
  document's honesty about which suites exist. Only IMPLEMENTED suites gate
  merges; moving a marker now requires the tests to exist.
- **Supersedes:** nothing. Every suite listed is still intended.
- **Tests:** none required — this withdraws claims rather than making them.
  `AGENTS.md` hard rule 7 was amended in the same change, since it required a
  property-based suite green before merge and no such suite can run.
- **Migration:** none.

*Caught by the `design-changelog` CI job on its first ever execution: the job
only runs on pull requests, and it failed this one for amending an AGREED design
without recording it. The guardrail worked on the author who wrote it.*

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
