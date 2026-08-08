# Core — Lending Module Design

**Status:** AGREED v1.19 (2026-08-08) — amendments via [`CHANGELOG.md`](CHANGELOG.md)

The fifth domain module ([ADR 0013](../../../docs/adr/0013-lending-module-first.md)):
loan origination, schedules, disbursement instructions, repayment allocation,
interest accrual and delinquency classification (PRD §4.5). Lending decides
what money should move and why; **every actual movement is an Orchestration
saga**, which is what keeps hard rule 3 intact and this module extractable.

This document is the module's full design — decisions, data model, engine
rules, API surface and test plan in one place, because a module earns separate
deep-dive files the way Orchestration did: by having them argued apart. Rows
move from the API section here into `api.md`'s enforced table as endpoints are
built — `ApiSurfaceCatalogTest` fails a documented-but-unbuilt route, and that
is a feature this document must not fight.

---

## 1. Scope

**v1 — the pilot's loan desk:**

- Individual loans against a published loan product: application →
  amount-tiered approval → offer → acceptance → disbursement → schedule →
  repayments → closure.
- **The decisioning spectrum, one model (PRD §4.5):** approval tiers start at
  zero. A tier requiring no approvals is instant lending under a configured
  ceiling (the deterministic policy decides; scores only advise, constitution
  12); a one-approval tier is a solo lender approving their own book — the
  mandatory maker-checker list in PRD §6.3 does not include loan approval, and
  a one-person institution is a supported tenant shape; an N-approval tier is
  a credit committee. Same table, same audit trail, same events — the segments
  differ by configuration, never by code path.
- **Early settlement:** a payoff quote as of a value date; flat-rate loans
  rebate unearned interest (the quote must survive an early exit honestly),
  declining-balance loans may carry a configured prepayment fee. Settlement is
  an ordinary repayment saga whose allocation reaches payoff.
- Schedule engine: **annuity** (equal installment), **flat**, **bullet**;
  grace periods on principal; day-count **ACT/365 fixed**.
- Daily interest accrual with value-dated recognition postings.
- Deterministic repayment allocation, order configurable per product
  (default: penalties → fees → interest → principal).
- Delinquency: daily classification into CBN MFB buckets
  (1–30 / 31–60 / 61–90 / 90+), PAR analytics by product, officer and
  organizational unit (ADR 0012's dimension, earning its keep).

**Deferred, deliberately, each with its trigger:**

- **Group lending** (Grameen-style) → first cooperative design partner; also an
  ADR 0013 extraction trigger.
- **Guarantors & collateral** → first secured-lending tenant. The design is
  pre-shaped: a lien on savings is a **Ledger hold** on the pledged account
  (the hold machinery is exactly this — no new ledger concept), guarantor
  undertakings are lending-schema rows with their own exposure view, and
  movable collateral registers through an NCR connector hook (connector era).
  Collateral release joins the closure workflow when this lands.
- **Credit life insurance** → first insurer relationship: premium as a
  disbursement-time deduction per product configuration, remitted as a partner
  settlement.
- **Top-up / refinance** → first pilot request: a *new* loan whose disbursement
  settles the predecessor's payoff, linked for history — never an in-place
  schedule edit.
- **Collections rails** (direct-debit e-mandates, GSI recovery, payroll
  deduction files) → the NIBSS connector. Lending owns *what is due*;
  connectors own *how it is collected*. Until then, repayments arrive as
  book transfers like everything else.
- **Disclosure pack** (total cost of credit / effective rate on the offer,
  FCCPC data points, cooling-off) → country-pack configuration with Compliance
  & Reporting; the offer's data model carries the computed totals from day one
  so the pack renders from facts already recorded.
- **Restructuring, rescheduling, write-off** → designed at the data-model level
  (nothing here forbids them) but no workflow in v1; they arrive with the
  pilot's collections practice, because designing a write-off approval chain
  without a compliance officer in the room produces fiction.
- **Credit bureau hooks** → with the connector framework; an extraction trigger.
- **Moratoriums and custom schedules** → v1.1; the schedule table is shaped to
  hold them (any installment row is data, not formula).

## 2. Decisions

**Loan products are Product configuration.** A loan product is a `products`
row of type `LOAN` whose versions carry lending rules the same way savings
versions carry fee rules: interest rate (annual, basis points), schedule kind,
term bounds, amount bounds per KYC tier, penalty rules, allocation order.
Lending evaluates a published version and records `product_version` on the
loan — the same "explicable after the configuration moves on" property sagas
have. Product's maker-checker publish flow is the approval of the *pricing*;
the loan's approval chain is the approval of the *exposure*. Two controls, two
questions, never conflated.

**Origination is a state machine, and money only moves at one edge.**
`APPLIED → APPROVED → OFFERED → ACCEPTED → DISBURSING → ACTIVE → CLOSED`
(plus `REJECTED`, `WITHDRAWN`, `EXPIRED` as terminals before money, and
`WRITTEN_OFF` reserved for the deferred workflow). Only the
`ACCEPTED → DISBURSING` edge touches money, by opening an Orchestration
transfer saga (loan funding account → customer account) under an idempotency
key derived from the loan id. `DISBURSING → ACTIVE` follows the saga's
outcome, three-valued like everything else: a definite failure returns the
loan to `ACCEPTED` with the refusal recorded; an unknown leaves it
`DISBURSING` — the saga's own worker and ops case machinery already own that
uncertainty, and Lending does not build a second copy.

**Approval chains are amount-tiered, from zero, Lending's own.**
`lending.approval_tiers` (per tenant: ceiling amount → approvals required,
**zero permitted**) drives how many distinct approvers an application needs.
A zero tier auto-approves by deterministic policy and records
`system:lending-policy` as the approver — attributed like any human decision,
because "nobody approved this" and "the policy approved this" must never read
alike in an audit. Where approvals are required, every row records who, when,
in which unit (the ADR 0012 snapshot), and applicant ≠ approver is a schema
CHECK — which for a solo lender means self-originated applications above their
own zero-tier ceiling need a second principal, and that is a *configuration*
conversation at onboarding, not a code path. Deliberately not Orchestration's
`approvals` table: that one is bound to a saga and an amount, this one to an
application and a chain — and modules do not share tables.

**The schedule is rows, not a formula.** Generation happens once, at
disbursement, into `loan_schedule` — one row per installment with due date,
principal due, interest due — and the engine that produced it is never needed
to *read* a schedule. Integer minor units throughout; rounding is deterministic
half-even per installment with **the final installment absorbing the residue**,
so the schedule sums exactly to principal plus computed interest, provable by a
CHECK-friendly invariant rather than by trusting arithmetic.

**Accrual is computed daily; income is recognized on collection, per
repayment (v1.17).** A daily job (worker-style, cross-tenant, lease-claimed)
advances each active loan's `accrued_interest_minor` using ACT/365 on the
outstanding principal. Recognition — the ledger posting that moves collected
interest (and penalties) into the product's income account — happens **when a
repayment allocates**, as a `RECOGNITION` funding saga (loan funding account →
`loan_rules.interest_income_account_id`; penalties to
`penalty_income_account_id`, falling back to the interest account) under keys
derived from the repayment: `lending:recognize:{repaymentId}:interest` and
`:penalty`. Per-repayment keys, deliberately, instead of the v1.16 sketch's
month-end delta under a month key: a month-scoped key cannot be replay-stable
once the delta moves between reruns (the fingerprint would mismatch and the
key-reuse guard would refuse — correctly), while a repayment's portions are
fixed forever the moment allocation commits. The daily job's `recognize()`
pass is the *catch-up*: any `ALLOCATED` repayment not yet marked
`recognized_at` is re-driven through the same keys, so a crash between
allocation and recognition converges instead of double-posting. Versions
without an income account recognize as an explicit no-op (marked, so the gap
in the books is a recorded fact that ages out as versions republish — the fee
account fallback's shape). `recognized_interest_minor` on the loan advances
only by amounts actually posted, so collected-vs-recognized is always a
subtraction; value-dated month-end postings wait for the ledger's value-dating
amendment and are out of v1.17's scope.

**Repayment allocation is a pure function, recorded as rows.** A repayment
arrives as an Orchestration saga (customer account → loan repayment account);
on its completion Lending allocates the amount across the outstanding
components in the product's configured order, writing one
`repayment_allocations` row per component touched. Partial payments allocate
as far as the money reaches; overpayment beyond payoff is refused at intake
(422), not parked. Given the same loan state and amount, allocation is
byte-identical on replay — property-tested, because "deterministic" in a
design doc is a wish until a generator disagrees.

**Penalties are pricing, charged by the daily job, idempotently (v1.17).**
Penalty rules live on `product.loan_rules` like every other price: a flat
amount per late installment (`penalty_flat_minor`, charged once per
installment, arbitrated by a `penalty_applied_at` marker on the schedule row)
plus basis points **per day** on overdue principal (`penalty_rate_bp`,
advanced through a `penalty_through` date exactly the way accrual advances
`accrual_through`), under an optional lifetime cap (`penalty_cap_minor`).
Charges accumulate in `penalty_charged_minor` on the loan; collections in
`penalty_paid_minor`; due is always the subtraction, never a third counter to
drift. Each charge emits `loan.penalty_applied`. The allocation engine's
`PENALTY` component stops being a no-op — money reaches it in the product's
configured order — and payoff (and the `REPAYMENT_EXCEEDS_PAYOFF` guard)
includes penalty due. A same-day rerun charges zero by construction: no
unmarked late installments, zero days advanced.

**Disbursement funding account is configuration-first (v1.17).**
`loan_rules.funding_account_id` names the tenant's loan funding account on the
version; when present it overrides the caller-supplied account on disburse,
which remains only a fallback for versions predating the column — the
`fee_rules.fee_account_id` pattern, ageing out the same way.

**Delinquency is classified daily, from the schedule, idempotently.** The job
finds the oldest unpaid installment per active loan, computes days past due,
and writes bucket transitions to an append-only `delinquency_events` table.
Classification per CBN MFB guidelines is *facts about days and buckets*;
regulator rendition formats stay out (Compliance & Reporting's job, when it
exists). PAR analytics are reads over schedule + events, grouped by the
dimensions already in the schema: product, officer principal, unit code.

**Events, from Lending's own outbox** (the "each emitting module owns one"
doctrine, honestly this time): `loan.application_received`, `loan.approved`,
`loan.offer_accepted`, `loan.disbursed`, `loan.repayment_allocated`,
`loan.delinquent`, `loan.recovered`, `loan.penalty_applied` (v1.17),
`loan.closed`. Payloads are thin (ids,
amounts as decimal strings, bucket names) per ADR 0008.

## 3. Data model — schema `lending`, role `core_lending`

The ADR 0007 pattern, verbatim: `tenant_id` everywhere, composite
`(tenant_id, id)` keys and FKs, RLS ENABLEd + FORCEd + policy per table, a
worker policy for the cross-tenant jobs, transaction-local tenant context.

| Table | Owns | Shape notes |
|---|---|---|
| `loan_applications` | The request and its lifecycle before money | state CHECK per §2; `product_code` + `product_version` pinned at approval; `officer` principal and `unit_code` snapshot (attribution, never authorization); amount, term, purpose; offer economics recorded at OFFERED (total interest, total cost, effective annual rate in basis points) — the disclosure pack renders from these facts later, so they are recorded now |
| `loan_approvals` | The chain, append-only | FK to application; `approved_by`, `approved_in_unit`, sequence no; CHECK maker ≠ approver; unique (application, approver) |
| `approval_tiers` | Tenant config: ceiling → approvals required | Small, admin-maintained; consulted at application submit |
| `loans` | The live obligation | Created at disbursement; principal, rate bp, day count, schedule kind, `disbursement_saga_id` (no FK — another module's row, referenced by id like ledger ids are), state `ACTIVE/CLOSED/WRITTEN_OFF`, `accrued_interest_minor`, `accrual_through` date; v1.17: `interest_paid_minor`, `recognized_interest_minor` (advances only by posted amounts), `penalty_charged_minor`, `penalty_paid_minor` (CHECK paid ≤ charged), `penalty_through` date |
| `loan_schedule` | One row per installment | due date, principal_due, interest_due, paid amounts per component, `settled_at`; generated once; append-only except the paid columns; v1.17: `penalty_applied_at` marks the once-per-installment flat penalty |
| `repayments` | One row per repayment saga | `repayment_saga_id`, amount, received date; unique per saga id — the saga's idempotency is Lending's; v1.17: `recognized_at` marks income recognition resolved (posted, or an explicit unconfigured no-op) |
| `repayment_allocations` | The split, append-only | FK to repayment; component CHECK (`PENALTY/FEE/INTEREST/PRINCIPAL`), amount, installment ref |
| `delinquency_events` | Bucket transitions, append-only | loan, from-bucket, to-bucket, days past due, as-of date; unique (loan, as_of) |
| `outbox_events` | Lending's events | Identical shape to orchestration's; relay grows one more table to poll |

Invariant-bearing constraints, schema-enforced as always: schedule sums to
principal + computed interest (deferred CHECK via trigger at generation);
allocations per repayment sum to the repayment amount; paid components never
exceed due components; terminal application states are terminal.

## 4. API surface (moved to `api.md` — built, v1.16)

| Method & path | Purpose | Permission |
|---|---|---|
| `POST /v1/loan-applications` | apply against a loan product | `loans:apply` |
| `GET  /v1/loan-applications/{id}` | state, chain progress | `loans:read` |
| `POST /v1/loan-applications/{id}/approve` | one approval in the tiered chain (approver from token) | `loans:approve` |
| `POST /v1/loan-applications/{id}/reject` | terminal, reason required | `loans:approve` |
| `POST /v1/loan-applications/{id}/accept-offer` | customer acceptance, recorded attributed | `loans:offer` |
| `POST /v1/loan-applications/{id}/disburse` | opens the funding saga; idempotent per application | `loans:disburse` |
| `GET  /v1/loans/{id}` | balances: principal outstanding, accrued interest, next due | `loans:read` |
| `GET  /v1/loans/{id}/schedule` | the installment rows | `loans:read` |
| `POST /v1/loans/{id}/repayments` | intake: opens the repayment saga, then allocates on completion | `loans:repay` |
| `GET  /v1/portfolio/par` | PAR by bucket × product × unit × officer | `loans:portfolio` |

New permissions join the realm and the job composites (`job:loan-officer`
composing apply/read/repay; `job:supervisor` gaining `loans:approve`;
`job:admin` gaining tiers administration). Denials stay body-less.

### Error catalog (moved to `api.md`'s tables — built, v1.16)

`LendingErrorCode`, module-local like every other catalog, catalog-tested on
arrival: `LOAN_NOT_FOUND` (absent or another tenant's, indistinguishable),
`APPLICATION_STATE_INVALID` (with `reason` naming the refused transition),
`PRODUCT_NOT_LENDABLE` (no published LOAN version in effect),
`AMOUNT_OUT_OF_BOUNDS`, `TERM_OUT_OF_BOUNDS` (both per product version),
`APPROVAL_SEQUENCE_INVALID` (duplicate approver, applicant approving, or a
tier already satisfied), `OFFER_EXPIRED`, `REPAYMENT_EXCEEDS_PAYOFF` (422 at
intake — overpayment is refused, never parked), `LOAN_NOT_ACTIVE`. Denials
stay body-less; not-found and wrong-tenant stay indistinguishable.

## 5. Testing (implemented, v1.16 — see the suites in `app`'s tests)

- **Schedule properties** (jqwik): for arbitrary principal/rate/term per
  schedule kind — sums exact to the minor unit, non-negative components,
  monotone dates, final-installment residue bounded by installment count.
- **Allocation properties**: deterministic on replay; conservation (allocated
  = received); order respected per product config; overpayment refused.
- **Origination chain**: tier boundaries (amount at/above ceiling), maker ≠
  every checker, double-approval by one principal refused by the unique key,
  terminal states terminal — schema-fired, in the `OrchestrationSchemaTest`
  style.
- **Disbursement outcomes**: definite failure returns to `ACCEPTED`; unknown
  stays `DISBURSING` and resolves through the saga worker — the
  failure-injection suite gains a lending scenario per crash window.
- **Accrual**: day-count golden vectors (incl. Feb/leap year); recognition
  reconciles — the invariant-6 job extends to accrued-vs-recognized at every
  boundary.
- **Delinquency**: bucket edges at 30/60/90; idempotent daily rerun; recovery
  transition on settlement.
- **Deny-by-default probes** on every endpoint, and cross-tenant invisibility,
  as everywhere.

### Operations

Every alarm ships with its measurement, per the scaffold's rule: gauges for
accrual lag (loans whose `accrual_through` trails the business date — the
number a stalled accrual job is caught by), delinquency-job staleness (newest
`as_of` vs today), disbursements stuck in `DISBURSING` beyond the saga
escalation bound, and portfolio-at-risk totals per bucket. The accrual and
delinquency jobs are lease-claimed and idempotent, so a crashed run's
successor repeats it whole; both log a run summary and neither alerts by log
line alone. v1.17 adds `core.lending.recognition.pending` — allocated
repayments whose income recognition has not yet resolved; a stalled
recognition catch-up is caught by this number climbing, the same way the
outbox's lag catches a stalled relay.

## 6. What this changes elsewhere (the implementation PR's checklist)

`ModuleBoundaryTest` — amend "nothing depends on orchestration" to exempt
lending, add "orchestration does not know lending". `FlywayConfiguration`,
`ModuleDataSources`, application.yml, Dockerfiles, `db/init`, CI roles —
the organization module's checklist, replayed. Relay: poll lending's outbox.
Realm: permissions and composites above. `api.md` and `testing.md`: rows move
in as they become true, never before.
