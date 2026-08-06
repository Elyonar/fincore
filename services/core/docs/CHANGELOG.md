# Core — Design Changelog

Amendments to the **agreed** Core design. Process and entry format:
[`docs/conventions/design-changes.md`](../../../docs/conventions/design-changes.md).

The version here matches the status header in every Core design doc. Newest
entry first.

---

## [1.3.0] — 2026-08-06 · MINOR

**The operator surface exists, and events can reach a broker.**

- **Docs:** `design.md`
- **Why:** approvals and the unresolved-outcome queue were in `api.md` and
  reachable only from code, which left an operator with no way to do their job
  except through the database. Core's relay likewise had only a logging adapter,
  so `transfer.completed` was written and relayed to nowhere.
- **Impact:** additive. New endpoints under `/v1/approvals` and `/v1/ops`; a
  Kafka publisher selected by `fincore.core.events.broker=kafka`, with the
  logging adapter still the default so a developer without a broker gets a
  working system rather than a startup failure.
- **Supersedes:** nothing.
- **Tests:** `OpsApiTest` — including that **no endpoint accepts an outcome**.
  `resolve` asks Core to try again and lets the Ledger answer; the moment an
  operator can assert what happened, the outcome protocol's central guarantee
  becomes advisory. That test is the one that should have to be deleted, visibly,
  before such a parameter could ever be added.
- **Migration:** none.

*Also records why the publisher adapters remain duplicated with the ledger's
rather than extracted to `libs/`: the two abstractions differ — batch-with-acks
against single-throw — so unifying them is a contract change to two AGREED
designs and belongs in its own PR.*

---

## [1.2.0] — 2026-08-06 · MINOR

**The Ledger client carries the tenant, and the contract suite that found it is
recorded.**

- **Docs:** `testing.md`, `architecture.md`
- **Why:** the contract suite ran against a real Ledger for the first time and
  immediately found that the client never sent `X-Tenant-Id` — Core could not
  talk to a real Ledger at all. Every other test in the module runs against a
  stub, which proves what Core does with an answer but not that the answer is
  the one the Ledger gives. It also found `ALREADY_REVERSED` classifying as
  `UNKNOWN`, because the winning reversal's id arrives in `detail` rather than
  the field the client read; a losing reversal would have retried forever.
- **Impact:** internal. `LedgerClient` gains a tenant parameter on every call —
  the tenant is not part of the money movement, it is whose movement it is, and
  the Ledger scopes every query by it. No API of Core's own changes.
- **Supersedes:** nothing.
- **Tests:** `LedgerContractTest`, tagged `contract` and excluded from the
  default build so it cannot pass by being absent.
- **Migration:** none.

*Also records the connection posture that was applied but undocumented: one pool
per module sized deliberately rather than left at the driver default, a short
connection-timeout on the money path as backpressure, and the reason a
transaction-mode pooler is available to this platform — tenant context is
`SET LOCAL` and no transaction is held across an outbound call. Raising a
database's `max_connections` is a local convenience, not the production answer.*

---

## [1.1.1] — 2026-08-06 · PATCH

**Persistence approach recorded: JDBC in `orchestration`, deferred for the other
two modules.**

- **Docs:** `design.md`
- **Why:** `docs/conventions/service-scaffold.md` requires every service to record
  whether it uses plain SQL or an ORM, and why. Core's design never did, so the
  choice existed only as whatever the first code happened to use — exactly the
  state the convention exists to prevent, and the one that gets re-litigated on
  every review.
- **Impact:** clarification, no behavioural change. It states a constraint that
  was already true and already implemented in `SagaClaims`.
- **Supersedes:** nothing.
- **Tests:** none required; no behaviour changes.
- **Migration:** none.

*Caught by a question rather than by review: "is it not time to use an ORM?" has
no answer in the documents unless the current choice is written down.*

---

## [1.1.0] — 2026-08-06 · MINOR

**One Maven module per domain, not two. Module boundaries move from the compiler
to ArchUnit plus database privilege.**

- **Docs:** `architecture.md`, `design.md`
- **Why:** v1.0 gave each domain a published `-api` artifact and an
  implementation artifact, so `orchestration` could depend on
  `core-customer-api` without customer's persistence ever reaching its
  classpath. It works, and at four modules it produced directory names —
  `services/core/core-product-api` — that read as ceremony rather than
  structure. Six Maven modules for three domains is a lot of scaffolding to
  carry before either domain has a second consumer.
- **Impact:** internal only; no caller contract changes. **One enforcement
  mechanism genuinely weakens**, and this entry exists to say so rather than
  imply the boundary is unchanged: orchestration now has customer's and
  product's implementations on its classpath, so the *compiler* will no longer
  stop it reaching into them. `ModuleBoundaryTest` will, and so will the
  per-schema database roles, which are untouched. Three mechanisms instead of
  four.
- **Supersedes:** "Cross-module calls go through published interfaces, enforced
  by the classpath" in the v1.0 decision log. The rule stands; its enforcement
  is now the boundary test and the `api`/`internal` package split.
- **Tests:** `ModuleBoundaryTest` — internals private per module, dependency
  direction, customer and product mutually ignorant, and only orchestration
  permitted an HTTP client. Its **empty-import canary earned itself on the first
  run**: ArchUnit 1.3 cannot read Java 25 bytecode and imported zero classes, so
  every other rule passed vacuously until the canary failed the build.
- **Migration:** none. Schemas and roles are unchanged.

*Recorded because the design was AGREED before the layout was built. The
convention says code contradicting an agreed design is a bug — so the design
moves first, in its own entry, rather than the doc being quietly reworded to
match what got written.*

---

## [1.0.0] — 2026-08-06 · AGREED baseline

**Initial agreed design.** Implementation may begin.

- **Docs:** [`design.md`](design.md), [`architecture.md`](architecture.md),
  [`data-model.md`](data-model.md), [`api.md`](api.md),
  [`saga-protocol.md`](saga-protocol.md),
  [`outcome-protocol.md`](outcome-protocol.md), [`testing.md`](testing.md)
- **Scope:** one deployable holding three modules — `customer`,
  `product`, `orchestration` — one PostgreSQL database, one schema and
  one database role per module. v1 covers book transfers only: deposit,
  withdrawal, intra-tenant transfer, business reversal, status lookup. No rails
  connectors, no holds, no standing orders, no interest accrual, no consumed
  events.
- **The central guarantee:** a request interrupted at any point ends either
  completely done or completely undone. It rests on the **three-valued outcome
  model** — `SUCCESS`, `DEFINITE_FAILURE`, `UNKNOWN` — and the single rule that
  compensation is legal only from `DEFINITE_FAILURE`.
- **Correctness lives in the schema and the key derivation:** ledger idempotency
  keys are a pure function of `(saga_id, step)`, so the ledger's mandated
  same-key retry is satisfiable; the saga row is persisted before any outbound
  call; terminal states and append-only attempt history are trigger-enforced;
  the unique index arbitrates duplicate submissions.
- **Boundaries are enforced four ways** — the classpath, per-module database
  roles, ArchUnit, and the POM dependency graph. Only `orchestration` may
  declare the ledger client.
- **No foreign key crosses a schema boundary**, which is what keeps
  `pg_dump --schema=` a real extraction path rather than a hopeful one
  ([ADR 0006](../../../docs/adr/0006-modular-core.md)).
- **Two kinds of reversal, never one code path:** compensating reversals are
  automated, self-targeted, and forbidden on `UNKNOWN`; business reversals are
  human and carry a single-use, amount-bound maker-checker approval.
- **Eight invariants**, including the cross-deployable one — Core and the Ledger
  agree, verified by scheduled reconciliation against the Ledger's read API.
- **Tests:** every suite in [`testing.md`](testing.md) runs against real
  PostgreSQL and, for the contract suite, a real Ledger — never in-memory
  substitutes. Failure injection is the primary suite, not a late addition.

**Known follow-ups**, tracked and not blocking implementation:

1. `libs/auth` and a deployed Keycloak realm model land before Core's first
   endpoint ([ADR 0010](../../../docs/adr/0010-keycloak-realm-per-tenant.md)).
   Retrofitting identity context is the expensive path.
2. CI provisions Core's database and its three module roles before the first
   migration. The workflow's lists are already generalised for it.
3. Post-restore reconciliation is operator-triggered in v1, because the Ledger
   stamps its epoch on events only and Core consumes none. Automatic detection
   arrives with event consumption, or sooner if the Ledger exposes its epoch on
   API responses — a Ledger amendment, not an assumption made here.
4. The Branch / organizational-unit domain is deferred; `tills` sits in
   `orchestration` with a stated move trigger (the teller application).
5. The extraction rehearsal required by
   [ADR 0006](../../../docs/adr/0006-modular-core.md): once the modules exist and
   the first vertical slice works, extract `customer` on a throwaway branch
   to establish what extraction actually costs.

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
