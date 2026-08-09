# Core — Design Changelog

Amendments to the **agreed** Core design. Process and entry format:
[`docs/conventions/design-changes.md`](../../../docs/conventions/design-changes.md).

The version here matches the status header in every Core design doc. Newest
entry first.

---

## [1.22.0] — 2026-08-09 · MINOR

**Job titles, staff numbering and the employment record** — the administration surface can now
describe an institution's own staff rather than storing whatever was typed on the hiring form.

- **API (new in `api.md`):** `GET /v1/job-titles`, `POST /v1/job-titles`,
  `DELETE /v1/job-titles/{title}`, `GET /v1/staff-numbering`, `PUT /v1/staff-numbering`,
  `PUT /v1/users/{id}/employment`. Reads take `users:read` because every screen showing a person
  shows their title; writes take `users:manage`.
- **A title is not a role.** Kept on a separate surface from `/v1/roles` deliberately: a role is
  what somebody may do and is checked on every request; a title is what they are called and is
  checked nowhere. Institutions that conflate them encode place and seniority into permission sets
  — `job:teller-lagos` — which is the multiplication
  [ADR 0012](../../../docs/adr/0012-organizational-units.md) exists to prevent.
- **`PUT /v1/users/{id}/employment` closes a real gap.** Staff number, job title and start date
  could only be set at creation, so everybody hired before an institution settled on its titles —
  including the seeded administrator — was permanently blank with no endpoint able to fix it.
- **Records live in the directory**, as with every other staff fact
  ([ADR 0018](../../../docs/adr/0018-first-party-identity-service.md)); Core proxies with the
  service credential plus the initiating administrator's forwarded token, and the directory's
  refusal codes (`JOB_TITLE_EXISTS`, `JOB_TITLE_UNKNOWN`, `JOB_TITLE_IN_USE`,
  `STAFF_NUMBER_TAKEN`) pass through unchanged — Core translates only where the two catalogs spell
  the same thing differently, and here they do not.
- **Not maker-checked**, and that is the same reasoning as staff creation: the guardrail that
  matters is that nobody grants what they do not hold, and a job title grants nothing at all.

---

## [1.21.0] — 2026-08-09 · MINOR

**Staff administration is built** — the slice of [`admin-surface.md`](admin-surface.md) §5 the
AGREED table does not mark maker-checked, plus two operational rows added by this amendment.

- **API (new in `api.md`):** `GET /v1/permissions` and `GET /v1/roles` (`users:read`);
  `POST /v1/users`, `GET /v1/users`, `GET /v1/users/{id}` (`users:read` / `users:manage`);
  `PUT /v1/users/{id}/units`, `POST /v1/users/{id}/reset-password`,
  `POST /v1/users/{id}/unlock` (`users:manage`).
- **New by amendment, not in the §5 table:** `reset-password` and `unlock`. An administrator who
  cannot restore a colleague's access on the first morning does not have an administration
  surface. Both are attributed and audited in the directory like every other mutation.
- **Deliberately still absent:** `POST /v1/roles`, `PUT /v1/roles/{role}/permissions`,
  `DELETE /v1/roles/{role}`, `PUT /v1/users/{id}/roles`, `deactivate`, `reactivate`.
  [ADR 0017](../../../docs/adr/0017-tenant-defined-roles.md) guardrail 3 requires a second
  signature on each, and Core's approval machinery is bound to a transaction id and an amount —
  a maker-checked write shipped without its second signature is worse than one not yet shipped.
  Extending `orchestration.approvals` to carry an approval *kind* is the work these wait on.
- **Where it lives:** Core owns the product surface; the identity service owns the records
  beneath it ([ADR 0018](../../../docs/adr/0018-first-party-identity-service.md)). Core
  authenticates to the directory as a service with a client-credentials token it mints and
  re-mints for itself — closing the static-token residual — and forwards the administrator's own
  token beside it, the ledger's `X-Forwarded-Authorization` pattern.
- **The units drift is closed for this path.** `PUT /v1/users/{id}/units` and creation with units
  write *both* stores: Core's `unit_assignments` and the directory's `units` claim. ADR 0017 names
  their divergence as a live defect — assignment was the documented system of record while nothing
  derived the claim, so putting a teller in a branch had no effect on authorization. Core's record
  is written first because it is the one that can refuse: a code naming no active unit stops the
  whole operation, so the two cannot disagree because half of it succeeded. The per-unit
  assignment endpoints are unchanged and still write Core's record alone.
- **Module contract:** `OrganizationUnits` gains `replaceAssignments` and `assignmentsOf`, and a
  `NoSuchUnit` refusal declared on the port rather than beside the adapter — the caller that must
  catch it lives outside the module, and `internal` is not importable.
- **Refusals:** `USER_EXISTS`, `ROLE_NOT_FOUND` (translated from the directory's `ROLE_UNKNOWN`),
  `PERMISSION_NOT_HELD_BY_GRANTOR` (403), `UNIT_NOT_FOUND`, `DIRECTORY_UNREACHABLE` (503).
- **Honest edge:** the catalog and surface tests for these rows are **PLANNED**. The path was
  exercised end to end against a running stack — login, catalog, roles, create staff, dual-write,
  reset — and hand-reviewed; CI is the gate.

---

## [1.20.0] — 2026-08-08 · MINOR

**The administration surface is designed.** What a tenant's own administrator needs to turn a
provisioned tenant into an institution that can transact — designed as one batch, implemented
next.

- **Docs:** new [`admin-surface.md`](admin-surface.md); no change to `api.md`, deliberately —
  `ApiSurfaceCatalogTest` fails a documented-but-unbuilt route in both directions, so planned rows
  live in the design doc and move into `api.md` as they are built, exactly as `ui-runway.md` §3
  works.
- **Why:** a tenant seeded by [ADR 0016](../../../docs/adr/0016-tenant-bootstrap-manifest.md) has
  a realm, three registry rows and one super-administrator who can sign in — and cannot make the
  institution take a single deposit. Three capabilities the schema already models are reachable by
  no endpoint: product pricing (`fee_rules`, `limit_rules`, `loan_rules` are written by nothing
  outside the test suite, so every published version prices nothing), opening a ledger account
  (Core's ledger client has no open operation and clients never address the ledger), and creating
  a second member of staff or composing a role.
- **Decisions:** a version is the unit of change and rules are replaced as a set per draft
  version, never patched row by row; `effective_from` becomes settable on a draft, closing PRD
  §4.3's effective-dated requirement against a column present and unreachable since V2; Core opens
  and links an account in one operation under a derived idempotency key, or does neither; the
  caller names a *purpose* rather than a ledger type, because opening a fee-income account and
  opening a customer account are different authorities; roles are namespaced server-side so a role
  can never be named after a permission
  ([ADR 0017](../../../docs/adr/0017-tenant-defined-roles.md) guardrail 0); no administrator may
  grant a permission they do not hold; user and role changes join the maker-checker set PRD §4.10
  has always required.
- **Impact:** backward compatible. No existing endpoint changes shape, no error code shifts
  meaning, no guarantee weakens. Four new permissions (`accounts:read`, `accounts:manage`,
  `users:read`, `users:manage`) join the catalog and `job:admin`.
- **Also closed:** the `units` derivation gap. `OrgUnitController` documents that the `units`
  claim is derived from `unit_assignments`; nothing derives it, so assigning a teller to a branch
  has had no effect on authorization. Assignment becomes one operation over both records.
- **Tests:** all PLANNED — `testing.md` markers move when the suites exist. The security core is
  role-name collision, grant-only-what-you-hold, last-administrator, and maker ≠ checker.
- **Migration:** none. Every table this surface writes already exists.

---

## [1.19.0] — 2026-08-08 · MINOR

**The UI runway is built.** The v1.18 design implemented whole; the platform is ready for client
development.

- **Identity, end to end.** `keycloak/realm-template.json` (the dev realm generalized: job
  composites, `units` claim, a PKCE-only `fincore-web` client, and a confidential `core` service
  client that deliberately carries no tenant claim) plus `scripts/provision-tenant.sh` — realm and
  registry row as one operation that rolls back rather than half-succeeds. `JwtEndToEndTest`
  drives a full disbursement on real RS256 tokens against a local JWKS: dev headers are inert in
  jwt mode, deny-by-default holds, and health stays open.
- **The ledger's tenant header is dead** (its own CHANGELOG 1.10.0 records the contract change).
  In jwt mode the ledger accepts only trusted service credentials (`core`); the originating user's
  token travels as `X-Forwarded-Authorization` and decides tenant *and* posting attribution —
  verified fact over copied string; the caller may assert a tenant only for its own system jobs.
  Core's ledger client sends its service credential and forwards the inbound bearer (outbound
  propagation — `libs/auth`'s recorded gap, closed).
- **The edge exists as configuration**: `edge/nginx.conf` + a compose service — one origin, path
  routing, bearer passed through, no ledger route by construction.
- **The nine read rows are served** (`api.md`): customer search (keyset-paged), customer accounts
  with balances joined from the ledger, the statement passed through byte-for-byte, the till's
  day, the checker's queue, applications by state / awaiting-me, a customer's loans, repayment
  history. Zero new permissions, zero new error codes.
- **Tests:** `UiReadsApiTest` (every screen-opener with the standing probes: deny-by-default,
  cross-tenant invisibility, keyset pagination staying disjoint and ordered),
  `JwtEndToEndTest`, and the ledger's `LedgerJwtAuthTest`.

---

## [1.18.0] — 2026-08-08 · MINOR

**The UI runway is designed (ADR 0014). Docs only — no code in this amendment.**

Every domain service the Phase 0 client apps need already exists; what stands between the APIs
and a browser is identity, the edge, and screen-shaped reads. This amendment agrees that bridge
before a line of it is built, per this platform's own rule.

- **Docs:** `ui-runway.md` (new), and ADR 0014 at the platform level
- **Decided:** the edge stays configuration (one reverse proxy, TLS, single origin — never a
  gateway service without a new ADR); clients speak only to Core, which proxies the ledger reads
  it chooses to expose (statements, balances) through the existing ledger client — hard rule 3's
  read-side completion; the ledger's tenant header dies as ADR 0010 promised (jwt via
  `libs/auth`, caller allowlist enforced, principal propagated into posting attribution); realm
  provisioning is a versioned template + a script that creates realm and registry row as one
  loud-failing operation; and the Phase 0 read audit is named screen-by-screen in
  `ui-runway.md` §3 — seven planned endpoints, zero new permissions, rows entering `api.md`
  only as they become true.
- **Explicitly out:** partner APIs, API keys, rate limits, webhooks, the provisioning
  dashboard, offline behavior, any new domain capability.

---

## [1.17.0] — 2026-08-08 · MINOR

**Lending closes its own three gaps: income recognition, the penalty engine, and the
configuration-first funding account.** The v1.16 "known gaps, honestly" list, closed.

- **Docs:** `lending.md`, `data-model.md`, `api.md`, `architecture.md`, `testing.md`, `design.md`
- **Income recognition, on collection, per repayment.** A `RECOGNITION` funding saga (V8 joins
  the type vocabulary) moves each allocated repayment's interest portion from the loan funding
  account to `loan_rules.interest_income_account_id` — and its penalty portion to
  `penalty_income_account_id`, falling back to the interest account — under keys derived from
  the repayment (`lending:recognize:{repaymentId}:interest` / `:penalty`). **Design deviation
  from the v1.16 sketch, recorded:** not a month-end delta under a month key, because a
  month-scoped key cannot be replay-stable once the delta moves between reruns — the fingerprint
  guard would refuse the reuse, correctly — while a repayment's portions are fixed forever at
  allocation. The daily job gains a `recognize()` catch-up pass over allocated-but-unresolved
  repayments; recognition is attempted inline after allocation and the job converges whatever a
  crash interrupted. Versions without an income account resolve as an explicit no-op (marked
  `recognized_at`, so the gap is a recorded fact that ages out on republish).
  `loans.recognized_interest_minor` advances only by amounts actually posted. Value-dated
  month-end postings wait on a ledger value-dating amendment — out of scope here.
- **The penalty engine.** Penalty rules join `loan_rules` (product V6): `penalty_flat_minor`
  once per late installment (arbitrated by `penalty_applied_at` on the schedule row),
  `penalty_rate_bp` per day on overdue principal (advanced through `penalty_through`, the
  `accrual_through` pattern), `penalty_cap_minor` as a lifetime cap. Charges accumulate in
  `loans.penalty_charged_minor`, collections in `penalty_paid_minor` (CHECK paid ≤ charged);
  due is a subtraction, never a third counter. The allocation engine's `PENALTY` component is
  live; payoff and `REPAYMENT_EXCEEDS_PAYOFF` include penalty due; each charge emits
  `loan.penalty_applied`. Same-day reruns charge zero by construction.
- **Funding account, configuration-first.** `loan_rules.funding_account_id` overrides the
  caller-supplied account on disburse; the caller's value remains only a fallback for versions
  predating the column — the `fee_rules.fee_account_id` pattern, ageing out the same way.
- **Operations:** `core.lending.recognition.pending` gauge (allocated repayments with
  recognition unresolved) — the number a stalled catch-up is caught by.
- **No new endpoints, no new error codes, no realm changes.** The API and error catalogs are
  unchanged; `GET /v1/loans/{id}` grows penalty/recognition fields in its body.

---

## [1.16.0] — 2026-08-08 · MINOR

**Lending is built.** The v1.15 design implemented whole: the fifth module, its schema, its
chain, its engine and its jobs — with the rows in `api.md` now true rather than staged.

- **Docs:** `api.md`, `lending.md`, `data-model.md`, `architecture.md`, `testing.md`
- **What exists now:**
  - `lending` schema (role `core_lending`, full ADR 0007 pattern): applications, append-only
    approvals, tiers (zero permitted), loans, generated schedules, repayments with append-only
    allocations, delinquency events, and lending's own outbox — which the shared relay now polls
    alongside orchestration's.
  - Product carries loan pricing: `LOAN` joins the type vocabulary and `loan_rules` (rate bp,
    schedule kind, bounds, grace, allocation order, prepayment fee, interest income account)
    rides product versions, evaluated through the `LoanProducts` port and pinned by version on
    the loan.
  - Orchestration publishes its surface: `MoneyMovements` (transfer / fund / status) is the api
    ADR 0013 promised, and funding sagas (`DISBURSEMENT`, `REPAYMENT`) are ordinary two-entry
    sagas minus the customer-channel gates — no product evaluation, no limit reservation, and
    V7 says why. A repayment still checks the customer holds the source account.
  - The thirteen `api.md` lending rows are served: origination through the tiered chain
    (zero-tier decisions attributed to `system:lending-policy`), offers with disclosure
    economics, disbursement idempotent per application with three-valued convergence, schedules
    as rows, repayment intake-then-allocate (exactly once, race-guarded by the repayment's own
    state), PAR by bucket × product × officer × unit, and the tier admin surface — logic without
    a route being a plan, not a feature.
  - Jobs, worker-identity, lease-safe, idempotent by construction: daily ACT/365 accrual
    (truncating — never a kobo nobody earned), delinquency classification into CBN buckets with
    append-only transitions and `loan.delinquent`/`loan.recovered` events, and convergence for
    DISBURSING applications and PENDING repayments whose sagas resolved while the caller was
    gone.
- **Known gaps, honestly:** interest **recognition postings** (accrued → income account) are not
  yet wired — accrual facts are tracked and reduced at allocation, and the income-side saga
  lands with the month-end job; the disbursement's `fundingAccountId` is caller-supplied
  operational reference (the tills gap's shape) until account metadata rides the ledger read;
  penalties price to zero pending the penalty engine.

---

## [1.15.0] — 2026-08-08 · MINOR

**Lending is designed (ADR 0013): Core's fifth domain module, above Orchestration. Docs only — no code in this amendment.**

- **Docs:** `lending.md` (new), `design.md`
- **Why:** the roadmap's next domain (PRD §4.5, Phase 0's loan flow) needed its
  packaging decided and its design agreed before a line of code, per this
  platform's own rule. ADR 0013 records the packaging: module-first, with named
  extraction triggers (group lending, credit-bureau connector, second team) and
  a dependency-order amendment — lending sits *above* orchestration, consuming
  its published transfer surface, so every disbursement, repayment and accrual
  recognition is a saga and hard rule 3 survives untouched.
- **What the design pins:** loan products as Product configuration of type
  LOAN (pricing maker-checked there; exposure approved in Lending's own
  amount-tiered chain, unit-attributed per ADR 0012); origination as a state
  machine where money moves at exactly one edge, three-valued like everything
  else; schedules as generated rows (ACT/365, half-even, final installment
  absorbs the residue — sums provable, not trusted); daily accrual with
  value-dated recognition postings, reconciled at every boundary by the
  invariant-6 machinery; deterministic repayment allocation as append-only
  rows; daily CBN-bucket delinquency classification with PAR by product,
  officer and organizational unit.
- **The full tenant spectrum, one model (PRD v1.9):** approval tiers start at
  zero — instant lending under a ceiling (policy decides, scores advise), the
  solo lender approving their own book, and N-approval committees are the same
  table, audit trail and events, differing by configuration only. Early
  settlement (flat-rate rebate, configurable prepayment fee) and the offer's
  disclosure economics (total cost, effective rate) are v1; the segments'
  remaining furniture is deferred **with named triggers**: guarantors &
  collateral (savings lien = a Ledger hold; NCR hook), credit life insurance,
  top-up/refinance, collections rails (e-mandate/GSI/payroll — connector era),
  the disclosure pack's rendition.
- **Deferred with triggers, not silently:** group lending, restructuring /
  write-off workflows, credit-bureau hooks, moratoriums.
- **Deliberately absent from `api.md` and `testing.md`:** lending's endpoint
  and suite tables live in `lending.md` until built — the catalog tests fail
  documented-but-unbuilt surface, and that property is worth more than a
  complete-looking table.

---

## [1.14.0] — 2026-08-08 · MINOR

**The safety net under the money path: invariant 6 runs, crashes are injected, alarms are measured.**

- **Docs:** `testing.md`, `data-model.md`, `design.md`, `architecture.md`
- **Why:** the service's two strongest claims — "Core and the Ledger agree" and
  "complete or compensated, never partial" — were prose. Invariant 6 was
  "specified and not yet running on a schedule"; the failure-injection suite was
  named "the primary suite here" and did not exist; and every documented alarm
  threshold had no measurement behind it.
- **What changed:**
  - **Reconciliation runs** (`internal/reconcile`, hourly by default, worker
    identity). Every saga COMPLETED inside the lookback window is read back
    from the Ledger: the transaction must exist and its debit total must equal
    principal + fee. A violation is an append-only
    `reconciliation_findings` row (unique per saga and kind — an unfixed
    mismatch never multiplies) and an ops case of new kind
    `RECONCILIATION_MISMATCH`, arbitrated by a one-open-case-per-saga partial
    index. An unreachable ledger records **nothing**: a maintenance window is
    not a discrepancy. The ledger client gains its first non-mutating read
    (`LedgerRead`: Found / NotFound / Unknown — three-valued like everything
    else). Deliberately not probed: FAILED sagas, because "the ledger posted
    but the saga failed" needs a read-by-idempotency-key the Ledger does not
    offer, and re-posting the key to find out could *make* it true. That half
    waits on a ledger amendment and testing.md says so.
  - **The failure-injection suite exists** and drives each crash window to
    convergence: after Phase A before the call, mid-call with the answer lost,
    after the ledger committed but before Phase C, and a worker dying
    mid-lease. The repeated assertion is the load-bearing one: recovery
    re-sends the *original derived key* — replay, never a second posting.
  - **The tenant business timezone is configuration**
    (`platform.tenants.business_timezone`, IANA id, default Africa/Lagos).
    It stopped being cosmetic when DAILY limits became enforced: the window
    rolls at the *tenant's* midnight now, not Lagos's. Unparseable zones fall
    back loudly rather than refusing money movement over a typo.
  - **`sagas.decision` is written.** The column existed since V2 and nothing
    populated it; Phase A now snapshots the whole decision (version, fee, fee
    account, both limits, channel, KYC tier), so a past transaction stays
    explicable to an examiner after the configuration moves on.
  - **Metrics exist** (`/actuator/prometheus`): outbox pending and oldest-age,
    outstanding sagas, open ops cases — read from the tables on scrape, never
    counted in memory, so a stalled relay still reports its backlog honestly.
- **Known gaps, honestly:** reconciliation covers COMPLETED sagas only (above);
  entry-level fee-account verification (which account the fee credited, not
  just the total) joins the suite when the read carries account roles.

---

## [1.13.0] — 2026-08-08 · MINOR

**Organizational units arrive (ADR 0012), and three caller assertions stop being taken on trust.**

- **Docs:** `api.md`, `design.md`, `data-model.md`, `architecture.md`, `testing.md`
- **Why:** tenant was the platform's only organizational axis. `tills.branch_code`
  was free text with no table behind it, the PRD's branch-scoped tokens had no
  claim to carry, and three authorization inputs on the money path were caller
  assertions: the channel (which selects limit rules), the fee account (where
  fee revenue lands), and the daily limit (documented, reserved against, and
  never actually summed — the reservation was written and never read back, so
  the race reservations exist to prevent was not prevented).
- **What changed:**
  - **New `organization` module** — the fifth (ADR 0006 machinery in full:
    `organization` schema, `core_organization` role, own Flyway history).
    `organizational_units` is a tree of typed, coded units whose codes never
    recycle; `unit_assignments` is the attributed record of who works where.
    Seven endpoints under `/v1/org-units` (`org:read` / `org:manage`).
  - **Tills gained their missing API** (`POST/GET /v1/tills`,
    `POST /v1/tills/{id}/close`, `tills:read`/`tills:manage`) — a till could
    previously be created only by a test or raw SQL while the cash path
    required one. Creation validates the branch through the `OrganizationUnits`
    port: an unknown, closed or non-BRANCH code is `UNIT_NOT_FOUND`, and the
    till records the unit's id alongside the code (plain column, no FK —
    another module's schema).
  - **Channel is permission-gated.** A closed set (`TELLER`, `API`); asserting
    one costs `channel:api` / `channel:teller`. An unmodelled channel is
    `COMMAND_INVALID` / `CHANNEL_INVALID`; an unlicensed one is 403. Cash
    endpoints take no channel field at all — cash is counter business, and the
    channel is the endpoint.
  - **DAILY limits are enforced** in Phase A, insert-then-verify under a
    per-window advisory lock: the reservation is written, the window summed
    (`RESERVED` + `CONSUMED`), and a breach rolls back saga, reservation and
    event together as `LIMIT_EXCEEDED` / `DAILY_LIMIT`. Product states the rule
    (`ProductDecision.dailyLimitMinor`); Orchestration holds the running total.
    `PER_TRANSACTION_LIMIT` now travels as the reason on per-transaction
    refusals — both reasons were documented and neither was ever attached.
  - **The fee-income account is product configuration.**
    `fee_rules.fee_account_id` (product V4); the decision carries it, the saga
    records the resolved account, and the posting credits it. The body field
    survives only as the documented fallback for published versions that
    predate the column — published versions are immutable, so they cannot be
    edited into carrying it.
  - **Approvals snapshot organizational scope**: `made_in_unit` /
    `checked_in_unit`, the token's `units` claim at the moment of signature.
    Attribution for the audit trail, never authorization — nothing branches on
    it.
  - **A dev-tenant seeder** (`fincore.core.dev-tenant-id`, compose-only, loud)
    is the first caller `TenantRegistry.register` has ever had — the v1.12
    known gap, half-closed: local stacks provision themselves; real
    provisioning still belongs to the unbuilt control plane.
- **Known gaps, honestly:** the fee-account fallback above; unit scope is
  attribution-only (no endpoint yet *requires* a unit — that arrives with the
  teller app); `unit_assignments` and the Keycloak `units` attribute are
  maintained separately until identity sync exists.

---

## [1.12.0] — 2026-08-07 · MINOR

**A tenant must exist before Core will serve it. `platform/V1__tenant_registry.sql`.**

- **Docs:** `architecture.md`, `testing.md`
- **Why:** row-level security isolates tenants from one another and has nothing
  to say about whether a tenant is *real*. Any UUID in a validated token was a
  working tenant: it got its own empty, functioning slice of Core, and every
  isolation test passed throughout — because isolation was never what was
  broken. A typo during provisioning would have produced a parallel universe of
  data that nobody noticed until someone asked where a bank's customers had
  gone. The ledger reached this conclusion in its own V6; this is the second
  deployable to need the same medicine, and it will not be the last.
- **Impact:** a request whose tenant is unregistered or suspended is refused with
  **404**, before any handler. 404 rather than 403 because telling a caller which
  tenant ids exist is an enumeration oracle, and a caller with no valid tenant
  has nothing legitimate to do with the answer — the same choice the ledger made
  and the same one Core already makes for another tenant's customer.
- **Design decisions worth recording:**
  - **Its own `platform` schema, not a module's.** A tenant is a fact about the
    deployable. Putting the registry in one module's schema would force the
    other two to read another module's table, which ADR 0006 forbids outright.
  - **One gate, at the request boundary.** Doing it per module would be three
    implementations of one rule, and two of them would eventually disagree with
    the third.
  - **Two identities in `TenantRegistry`.** The check runs as a module role
    holding SELECT; registration runs as the owner. So a request path cannot
    enrol its own caller's tenant even if somebody later wires an endpoint to
    that method — which would be back to trusting the claim.
  - **Suspension is the same refusal as absence.** Entitlements are a control
    plane concern and this is the seam they will act through: suspending a
    tenant has to stop traffic, not merely record an intention.
- **Tests:** `TenantGateTest` (5) — an unregistered tenant refused whatever its
  permissions say; the refusal not confirming what exists; a registered tenant
  served and then judged on permission; suspension closing an open tenant; and
  health and the service identity staying reachable, because an orchestrator's
  probe holds no token and a gate that demanded one would make the service
  unschedulable. Every existing test now registers the tenant it uses, rather
  than the gate being exempted under test — a guard switched off under test is a
  guard nobody has tested.
- **Migration:** `platform/V1__tenant_registry.sql`, which backfills from
  existing customers, sagas and products. A security fix that silently drops the
  traffic a deployment is already serving is a data-loss bug in better clothes.

*Known gap, stated rather than implied: nothing provisions a tenant. `register`
exists and no endpoint calls it, so today a tenant is created by the test suite
or by hand. The control plane that would own this is designed and unbuilt.*

---

## [1.11.0] — 2026-08-07 · MINOR

**Customers get a language. `customer/V6__customer_locale.sql`.**

- **Docs:** `api.md`
- **Why:** PRD §4.9 asks for Hausa, Yoruba and Igbo templates as tenant content,
  and Notification keys every template by locale — so the schema was ready and
  the *choice* was not. With nowhere to read a customer's language from, the
  sending service hardcoded `en` at exactly the point where selection belongs: a
  working system that could only ever speak one language, to a platform whose
  first market has three major ones besides English.
- **Impact:** additive. `locale` on `customers`, on the create request, on the
  profile response, and on the contact lookup that Notification calls on every
  send. Nothing existing changes shape.
- **Nullable, deliberately.** A customer nobody asked is the normal case, not an
  error, and the sender falls back to the tenant default rather than refusing to
  write. Storing `'en'` as a column default would be a guess wearing the shape of
  an answer — the same reasoning that keeps `communication_consent` to explicit
  answers only, and the lookup returns `null` rather than inventing one.
- **Supersedes:** nothing. It closes a gap that was recorded rather than hidden:
  the hardcoded locale carried a comment saying why it was there.
- **Tests:** `ContactAndConsentApiTest.locale_is_carried_and_never_invented` —
  the language reaches the lookup, and absence stays absent.
- **Migration:** `customer/V6__customer_locale.sql`.

---

## [1.10.0] — 2026-08-06 · MINOR

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

## [1.9.0] — 2026-08-06 · MINOR

**The suite gets its own database.**

- **Docs:** `testing.md`, `README.md`
- **Why:** the suite and `docker compose up` shared one database per service, and
  a running deployable is a concurrent writer. That produced two separate
  failures that looked like real defects and were not — `AnchorServiceTest` in
  the Ledger, because Core's saga worker and outbox relay poll on short
  intervals and PostgreSQL transaction ids are **cluster-wide**, so polling
  Core's database held the Ledger's quiesce horizon below entries a test had
  already committed; and `OutboxTest`, because `HoldExpirySweep` runs every
  thirty seconds inside the Ledger container and writes the very events that
  test counts. The first was fixed by teaching the tests to wait. The second is
  what said the problem is the shared database, not the assertions: no amount of
  draining or waiting inside a test makes a live writer go away.
- **Impact:** none on the product. All six module datasources default to
  `core_test`; `SPRING_DATASOURCE_URL` still overrides. Flyway builds it from the
  same migrations, so the schemas cannot drift.
- **Supersedes:** the assumption behind the port split — that moving host ports
  was enough for the stack and the suite to coexist. Ports were the visible half.
- **Tests:** whole suite green **with the stack running**, and the isolation
  demonstrated rather than asserted: the development databases' row counts were
  identical before and after a full run, while the test databases took every
  write.
- **Migration:** `db/init/50-test-databases.sql`. It runs only on an empty
  volume, so an existing checkout needs the database created in place — the
  command is in `README.md`. Nothing is dropped and no development data moves.

---

## [1.8.0] — 2026-08-06 · MINOR

**`GET /v1/transactions/{id}` names the accounts the money moved between.**

- **Docs:** `api.md`
- **Why:** `transfer.completed` carries `{transactionId, amountMinor, feeMinor,
  currency}` and `TransferResult` carried no accounts either, so **there was no
  path anywhere on the platform from "a transfer happened" to "whose account
  moved"**. For the channel that initiated the transfer this is invisible — it
  supplied the accounts. For a *consumer* it is fatal: ADR 0008 keeps PII and
  database shapes off the bus, so a consumer holds a saga id and reads state
  back through the publisher's API. Found by building the first consumer, which
  is the first thing that ever had to ask.
- **Impact:** additive. `TransferResult` gains `fromAccountId` and
  `toAccountId`, null on a reversal — which targets a transaction rather than a
  pair of accounts. No request shape changes, no endpoint changes, **no
  migration**: `V4__saga_posting_shape.sql` already added the columns for the
  recovery worker, which needs them to rebuild an identical posting under the
  same derived key.
- **Why the read API rather than the event payload**, since both were available:
  - ADR 0008 states the pattern outright — *"Consumers are state-based. React to
    an event by fetching current state through the publisher's read API."* This
    is the case that rule describes.
  - Payloads stay thin. Serving one consumer by widening an event starts the
    slide the ADR warns about: the next consumer wants its own field, and a
    payload becomes database-shaped one addition at a time.
  - A topic is retained for a long time and read by more consumers than an API
    is. Account identifiers are not PII, but the smaller surface is still the
    better default.
  - The accepted cost, stated rather than elided: a consumer now makes a call it
    would not have made, and its ability to address a customer depends on Core
    being reachable. That is the right dependency — a consumer that cannot reach
    Core should leave the event unconsumed for redelivery, not invent a
    suppression reason that is false.
- **Supersedes:** nothing. `TransferResult`'s doc comment now says why the two
  fields exist, because "the caller already knows this" is the reasonable
  objection and the answer is that the caller is not the only reader.
- **Tests:** `TransferApiTest.the_status_read_names_the_accounts_the_money_moved_between`.
- **Migration:** none.

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
