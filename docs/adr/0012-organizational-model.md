# ADR 0012 — Organizational units are operational scope, and nothing else

**Status:** Accepted · 2026-08-08
**Supersedes:** nothing. Names the boundaries around a concept three services were
about to need in three different shapes.

## Context

The platform had exactly one organizational axis: the tenant. Below it, nothing —
and the absence was starting to leave marks:

- `orchestration.tills.branch_code` was free text with no table behind it, no
  validation, and no query that used it.
- PRD §6.2 promises JWTs carrying "branch/agent scope", and the onboarding plan
  (§10) says "branches created as organizational units" — with no model for a
  token claim to name or a unit to create.
- `IdentityContext` carried tenant, principal, service and permissions, but no
  organizational scope; the only trace of a branch anywhere was the string
  `user:ada.o@branch-01` in doc examples, which nothing parsed.
- Reporting the PRD promises ("PAR>30 by branch") and licensing terms
  ("capped by accounts/branches") had no dimension to group or count by.

The tempting fix is one generic subdivision under the tenant — a `subcategory`
that is a branch here, a country operation there, a subsidiary elsewhere. The
industry evidence says no. Core-banking platforms do keep a subdivision beneath
the institution (Mambu's branches explicitly may represent "a geographical area,
product line, or another organizational division"; FLEXCUBE gives branches codes
and routing but scopes users *across* branches as authorization, not identity).
But regulators distinguish sharply between things that subdivision might be
asked to conflate: the ECB's AnaCredit methodology treats a foreign **branch**
as legally dependent on its parent while a **subsidiary** is a separate legal
entity, and Basel's liquidity principles require risk to be understood across
"legal entities, branches, subsidiaries and jurisdictions" — four dimensions,
named separately, because they answer different questions:

| Concept | Question it answers |
|---|---|
| Legal entity | Who legally owns the contract, account or liability? |
| Booking unit | Which accounting book records the transaction? |
| Organizational unit | Which part of the institution performed the work? |
| Jurisdiction | Which country's laws and regulatory rules apply? |
| Channel | Was it a branch counter, an app, an agent, an API? |

A Nigerian MFB's three branches are operationally independent — staff, tills,
limits, approvals, reports — while its customers' deposits remain liabilities of
one licensed institution. A cross-border fintech's "Nigeria" is not a branch at
all: it is a separately licensed legal entity with its own regulatory reports
and safeguarding arrangements. One generic subdivision would have to mean both,
and would therefore mean neither.

## Decision

**An organizational unit is operational scope, and only that.** It answers
"which part of the institution performed the work" — never who owns the
liability, which book records it, or whose regulator governs it.

The five dimensions land as follows:

1. **Legal entity ≙ tenant.** The platform already decided this without naming
   it: constitution rule 3 ("customers' money moves under customers' licenses")
   plus one Keycloak realm per tenant (ADR 0010) make the tenant the licence
   boundary. A subsidiary is therefore *its own tenant*, never a unit inside
   one. A group above tenants (SwiftPay Holdings over three country companies)
   is control-plane territory and is deliberately not modelled yet — it arrives
   with the reseller/group requirement, above the tenant, not below it.

2. **Organizational units are a first-class Core module.** `organization` owns
   the `organization` schema (role `core_organization`, the full ADR 0007
   pattern) with two tables: `organizational_units` — a tree of typed, coded
   units (`BRANCH`, `REGION`, `COUNTRY_OPERATION`, `BUSINESS_LINE`, `CENTRE`,
   `AGENT_NETWORK`, `DIGITAL_CHANNEL`, `OPERATIONS_TEAM`) — and
   `unit_assignments`, the attributed record of who works where. Codes are
   unique per tenant and never recycle; closed units keep their history.
   A new unit *type* is a migration plus a design amendment, not a free string:
   limit rules and reporting will group by it.

3. **Unit scope travels in the token, like permissions.** `IdentityContext`
   gains `units` — unit codes from a `units` claim the identity provider
   asserts. A caller never asserts its own scope; the assignments table is the
   system of record from which identity provisioning derives the claim. Empty
   means "no organizational scope", which restricts rather than permits.

4. **Booking units are deferred, and the ledger stays organization-agnostic.**
   The ledger is one book per tenant and knows nothing of branches — its
   data-model already says "never in this service", and that survives this ADR
   intact. Money never gains a `unit_id`; a till is *referenced by* its ledger
   account and *described by* its unit, in Orchestration. If a sub-book
   requirement ever arrives (it is a Phase-5-class concern at the earliest), it
   is a ledger amendment with its own ADR, not a column on entries.

5. **Jurisdiction is the country pack (PRD §3.1), not a unit.** A
   `COUNTRY_OPERATION` unit is how an institution *organizes* work in a
   country; which regulator's rules apply is configuration that arrives with
   Compliance & Reporting. The two must not be collapsed: several physical
   offices in one country may be one regulatory branch for reporting purposes.

6. **Channel is neither a unit nor free text.** It selects limit rules, which
   makes it an authorization input, so it is a closed set (`TELLER`, `API`)
   gated by permission (`channel:teller`, `channel:api`) — a caller may only
   transact as a channel its token licenses. New channels (USSD, AGENT) arrive
   by design amendment alongside the services that introduce them.

What consumes units today, deliberately little: tills validate their branch
through the `OrganizationUnits` port at creation; approvals snapshot the
maker's and checker's unit scope as audit attribution. Both are attribution and
reference-data validation — no money-path decision branches on a unit, and that
restraint is the point: the operational tree can be reorganized without a
migration touching money.

## Consequences

- Teller-app work (PRD Phase 4) lands on a modelled Branch: tills, supervisor
  approvals and branch reports have their dimension waiting, and
  `tills.branch_code` stops being the only branch in the building.
- Keycloak provisioning grows a second attribute to manage (`units`), and the
  gap between the assignments table and the claim is explicit: until identity
  sync exists, an admin maintains both, and the assignments table is the one an
  auditor believes.
- The deferred concepts have named homes waiting: group/reseller (control
  plane, above tenants), booking unit (ledger amendment, if ever), jurisdiction
  (country packs, with Compliance & Reporting). Anyone tempted to bolt one onto
  `organizational_units.unit_type` reads this ADR first.
- One more Core module means one more schema, role, datasource and Flyway
  history — the ADR 0006 machinery, which is priced and paid deliberately.
