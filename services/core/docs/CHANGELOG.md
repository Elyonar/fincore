# Core — Design Changelog

Amendments to the **agreed** Core design. Process and entry format:
[`docs/conventions/design-changes.md`](../../../docs/conventions/design-changes.md).

The version here matches the status header in every Core design doc. Newest
entry first.

---

## [1.8.0] — 2026-08-06 · MINOR

**An error contract a non-anglophone caller can render from.**

- **Docs:** `api.md` (error catalog rewritten, reasons table added)
- **Convention:** [`docs/conventions/error-contract.md`](../../../docs/conventions/error-contract.md)
- **Why:** Core's rejections were not machine-readable at all. The
  `IllegalArgumentException` handler put `e.getMessage()` into the `code` field,
  so a caller branching on the code was branching on an English sentence —
  literally `{"code": "amountMinor must be positive"}`. The same field also
  carried real codes like `WASH_TRANSACTION`, so what it held depended on which
  validation happened to fail first. A channel serving francophone customers
  could not translate either kind, and could not reliably tell them apart.
- **Change:** the error body gains `reason` and `details` and drops prose from
  `code`. `ErrorCode` is an enum, `TransferRefused` and `NotReversible` carry it
  rather than a free string, and command validation throws `CoreException`
  instead of `IllegalArgumentException`. `message` is now explicitly developer
  English: never displayed, never parsed, reworded without an amendment.
- **New codes documented:** `COMMAND_INVALID`, `TILL_NOT_OPEN`,
  `FEE_EXCEEDS_DEPOSIT`, `LEDGER_UNREACHABLE`, `OUTCOME_UNKNOWN`,
  `LEDGER_REFUSED` — all of these could already reach a caller; none was in the
  catalog. That gap is the reason the guardrail below exists.
- **The Ledger's codes are mapped, not forwarded.** A Ledger error Core does not
  model becomes `LEDGER_REFUSED` with the original in `details.ledgerCode`.
  Forwarding it verbatim would put codes in Core's responses that Core's own
  catalog does not document, silently merging two contracts.
- **Impact:** breaking for anyone who parsed `code` as prose — which nothing
  should have been doing, and which is exactly why this is worth fixing before
  Core has consumers. Callers keying on documented codes are unaffected.
- **Tests:** `ErrorCodeCatalogTest` — fails the build when a code or reason
  exists without documentation, and when `api.md` documents one that no longer
  exists. Mutation-tested in both directions before it was trusted. It also
  asserts every `ProductDecision.Refusal` has a matching `ErrorCode`, because
  `TransferService` maps them by `valueOf` and a drift there is a runtime
  failure on a money path.
- **Also:** `EXTERNAL_REF_TAKEN`, `ACCOUNT_ALREADY_HELD`, `REASON_REQUIRED`,
  `TIER_UNCHANGED`, `CONSENT_INCOMPLETE`, `PRODUCT_CODE_TAKEN`,
  `INVALID_PRODUCT_TYPE`, `PRODUCT_VERSION_NOT_FOUND`,
  `VERSION_ALREADY_PUBLISHED` and `PUBLISHER_IS_AUTHOR` — added in v1.7 as
  string literals in Customer's and Product's HTTP layers — become
  `CustomerErrorCode` and `ProductErrorCode`. Each module owns its own
  catalog: a single platform-wide enum would make every module compile
  against Orchestration, which is the cross-module dependency ADR 0006
  exists to prevent.
- **Migration:** none — no schema change.

---

## [1.7.0] — 2026-08-06 · MINOR

**Customer gains contact addresses and communication consent, and one lookup
that runs from an account.**

- **Docs:** `api.md` (v1.6)
- **Why:** Notification is the platform's first event consumer
  ([ADR 0011](../../../docs/adr/0011-first-consumer-before-phase-three.md)), and
  a domain event carries no PII by design (ADR 0008). A service that sends to
  customers therefore has to ask, on every send — and Customer could not answer.
  It held `phone` and nothing else: no second address kind, no consent records
  despite PRD §4.2 assigning them here, and **no way in from an account id**,
  which is the only identifier an event carries. `GET /v1/customers/{id}` needs
  a customer id, so the send path had no entry point at all.
- **Impact:** additive. Two routes —
  `GET /v1/customers/by-account/{ledgerAccountId}` and
  `POST /v1/customers/{id}/consent` — two permissions (`customers:contact`,
  `customers:consent`), one new error code, and `email` on the create request
  and the profile response. No existing endpoint or shape changes.
- **Design decisions worth recording:**
  - **The lookup carries its own permission and returns no name and no tier.**
    It exists for a machine, and a machine that sends messages should be able to
    hold exactly that grant. Reusing `customers:read` would have meant "let the
    notifier read contacts" implying "let it read everything".
  - **Addresses are returned keyed by address *kind*** (`PHONE`, `EMAIL`), not
    by channel. Several channels share a kind — SMS and WhatsApp are both
    `PHONE` — so a new channel on an existing kind needs nothing from Core at
    all. Only a genuinely new kind, such as a device token for push, is a change
    here. That is what keeps the channel registry on Notification's side cheap.
  - **Consent is per `(category, channel)`, never one flag.** "Accepts
    transaction alerts by SMS, refuses marketing, never asked about email" is
    one customer and three answers, and a single flag collapses them in the
    direction that sends.
  - **Absence is not denial.** Only explicit answers are stored, and the
    response carries them unchanged. What an *absent* answer permits is delivery
    policy — a transactional alert is a fraud control nobody opts out of,
    marketing is opt-in — and that belongs to the sending service. A default
    stored here would dress an assumption up as a customer's answer, which is
    also why `granted` is boxed and an omitted one is a `422`, not a `false`.
  - **`category` and `channel` are TEXT, not CHECK lists.** Notification adds
    channels as data; a CHECK here would mean a Core migration every time
    another service gained a delivery channel.
- **Tests:** `ContactAndConsentApiTest` (12) — addresses keyed by kind, absent
  addresses omitted rather than null, the narrow response, unknown and
  foreign accounts both 404, unlinking ending the lookup, the endpoint's own
  permission, consent per category and channel, `UNSET → GRANTED → DENIED`
  history with attribution, and the append-only trigger refusing both UPDATE and
  DELETE. `ApiSurfaceCatalogTest` and `CustomerApiTest` stay green.
- **Migration:** customer `V5__contact_and_consent.sql` — `email` on
  `customers`; `communication_consent` (current state, unique per customer,
  category and channel); `consent_changes` (append-only, trigger-enforced);
  RLS enabled and forced on both with the module's grants; and a partial index
  on `(tenant_id, ledger_account_id) WHERE unlinked_at IS NULL`, because the new
  lookup runs in the opposite direction from every existing query on that table
  and would otherwise scan on every send.

*Known gap, stated rather than left to be found: there is no endpoint that
**changes** a customer's phone or email after creation. Contact details do
change, and that is a real omission — but it is an administrative surface with
its own attribution and audit questions, not something to bolt onto a migration
whose purpose was to let a sender find an address.*

---

## [1.6.0] — 2026-08-06 · MINOR

**Every endpoint `api.md` documents now exists, and the document can no longer
drift from the code.**

- **Docs:** `api.md` (v1.5)
- **Why:** `api.md` was stamped AGREED and listed sixteen endpoints. Six were
  built. Customer had no HTTP surface at all and neither did Product — their
  modules held only the two narrow ports Orchestration reads through, so
  `customer.customers` and `product.products` were populated exclusively by
  tests issuing raw INSERTs. Deposits, withdrawals and business reversal were
  worse: all three are named in this CHANGELOG's own v1 scope, and
  `CashService` and `ReversalService` were built, tested and passing with no
  route to reach them. Logic without a route is not a feature, it is a plan, and
  a document that lists it beside working endpoints is telling an integrator
  something untrue.
- **Impact:** additive. Ten new routes — three wiring existing orchestration
  services (`POST /v1/deposits`, `POST /v1/withdrawals`,
  `POST /v1/transactions/{id}/reverse`), four for Customer, three for Product.
  Twelve new error codes. No existing endpoint, request shape or response shape
  changes. `api.md` gains a permission column, which had never been written down
  anywhere but the code.
- **Design decisions worth recording:**
  - Administration lives in each module's `internal` package, not its `api`
    package. `customer.api` and `product.api` are the contracts Orchestration
    reads through, and their narrowness is load-bearing — it is what keeps the
    money path free of PII and lets either module become its own deployable by
    turning an interface into a client. Widening them to carry an admin console's
    shape would give that up for nothing.
  - Maker-checker on publish is enforced by two names on one row, not by
    Orchestration's `approvals` table. Product may not depend on Orchestration,
    and an approval there is bound to a saga id and an amount — neither of which
    a product version has.
  - Each module now carries its own `@RestControllerAdvice`. Orchestration's
    cannot map Customer's or Product's exceptions without importing their
    internals, which `ModuleBoundaryTest` forbids. The boundary that keeps them
    extractable is the same one that rules out a single shared advice.
- **Two defects found by the new endpoints, both pre-existing:**
  - `customer.customer_accounts.one_live_holder_per_account` did not enforce its
    name. Written as `UNIQUE (tenant_id, ledger_account_id, unlinked_at)`, and a
    live link has `unlinked_at IS NULL` — PostgreSQL treats NULLs as distinct, so
    two live holders of one account inserted happily. That is the exact case the
    constraint's own comment said must never happen, and
    `CustomerEligibility.holdsAccount` — asked on every transfer and every cash
    operation — would have been answering from whichever row the planner
    returned. Nothing caught it because until now no code path could create a
    second link. Replaced with a partial unique index in customer `V4`.
  - `ReversalService.NotReversible` and `ApprovalRecords.ApprovalRejected` had no
    HTTP mapping and would have surfaced as 500s. Invisible while the reversal
    endpoint did not exist.
- **Supersedes:** `api.md`'s "OpenAPI is generated from the code once
  implementation lands", which framed the document as a target while it read as
  a description. The surface is now checked both ways by
  `ApiSurfaceCatalogTest`. Per-endpoint status markers were considered and
  rejected — a hand-maintained "not yet built" marker is the same category of
  artefact that went stale here, whereas a failing build is not.
- **Tests:** `CustomerApiTest` (14), `ProductApiTest` (11),
  `CashAndReversalApiTest` (10), `ApiSurfaceCatalogTest` (2). The last is
  modelled on the Ledger's `ErrorCodeCatalogTest` and carries the same
  empty-set canary, because a parser that silently matches nothing turns a
  bidirectional check into a decoration.
- **Migration:** customer `V3` (tier-change audit, append-only; `created_by`),
  customer `V4` (the live-holder index), product `V3` (`created_by` and
  `publisher_differs_from_author`). Product `V3` adds its column with a default
  rather than backfilling by UPDATE: V2's immutability trigger correctly refuses
  to update a published row, and refused this migration's first draft. **Customer
  `V4` fails loudly if any account is already live-linked to two customers** —
  choosing which customer keeps an account decides who may move its money, and a
  migration is the last place that should happen silently.

---

## [1.5.0] — 2026-08-06 · MINOR

**The envelope is rendered by `libs/events`, and gains `occurredAt`.**

- **Docs:** `architecture.md`
- **Why:** Core assembled the ADR 0008 envelope in its own relay, and the ledger
  assembled a different one in its. Comparing the two found three divergences —
  flat body against nested, `ledgerEpoch` against `epoch`, and no `occurredAt`
  on either — plus the ledger's outbox id missing from the wire entirely. Core's
  shape was the closer of the two, and still wrong: without `occurredAt` a
  consumer that only cares about the present cannot reject a stale event after a
  replay, and it cannot derive that time from anything else on the message.
  Found while designing the platform's first consumer, which is the first thing
  that would have had to live with it.
- **Impact:** the wire body gains `occurredAt` (the outbox row's `created_at` —
  when the state change committed, never when the relay ran). Field order now
  follows ADR 0008's table. No API of Core's own changes, and no schema change:
  `orchestration.outbox_events` already carried `tenant_id`, `epoch` and
  `created_at`.
- **Supersedes:** "the library carries events, not schemas, so wrapping is the
  service's job and stays here" — the comment in Core's relay that made the
  divergence structural. Wrapping is now the library's job precisely because two
  services each doing it produced two envelopes.
- **Tests:** `PlatformEnvelopeTest`, `PublishersSendTheEnvelopeTest`
  (`libs/events`). Core's 97-test app suite and 39-test orchestration suite are
  green, including `OutboxTest`'s interleaved-commit case.
- **Migration:** none.

*Unrelated but found in the same pass, and recorded so it is not rediscovered:
`architecture.md` documents a `transfer.reversed` event that the code does not
emit — the reversal path emits `transfer.reversal_initiated`. Doc and code
disagree; settling which is right is its own amendment, not this one.*

---

## [1.4.0] — 2026-08-06 · MINOR

**Publishers move to `libs/events`; RabbitMQ arrives as a consequence.**

- **Docs:** `design.md`, `architecture.md`
- **Why:** v1.3 recorded the duplication as deliberate and named its trigger. The
  trigger fired immediately — adding Core's Kafka adapter made two
  implementations of one idea concrete rather than hypothetical.
- **Impact:** internal, plus a configuration rename: the backbone is chosen by
  `fincore.events.broker` for every service. RabbitMQ now works for Core without
  a line of Core code, which is the point of the seam.
- **Supersedes:** the v1.3 note deferring the extraction, and Core's
  single-event publisher signature. The library took the ledger's
  batch-with-acknowledgements shape: a batch where the third send fails must
  still mark the first two published, or one unlucky event stalls everything
  behind it forever. Core's relay now marks published exactly what the broker
  acknowledged.
- **Tests:** `OutboxTest` unchanged in intent and passing against the new shape;
  `ModuleBoundaryTest` states that a shared library is not another deployable.
- **Migration:** none.

*Broker health indicators are disabled deliberately. A broker outage delays
delivery and must never make the service unhealthy — that is the entire reason
events are written to an outbox first, and readiness must not follow a dependency
the write path does not have.*

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
