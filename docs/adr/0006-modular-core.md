# ADR 0006 — Customer, Product and Orchestration ship as one Core deployable

**Status:** Accepted · 2026-08-05
**Supersedes:** nothing. Clarifies PRD §4 vs §9 rather than overriding either.

## Context

The ledger is built and the platform needs its second deployable. Three domains
sit between the channels and the ledger — Customer (§4.2), Product (§4.3) and
Transaction Orchestration (§4.4) — and the repository has been ambiguous about
whether they are three processes or one.

The ambiguity is genuine and worth naming, because both readings were defensible
from the same document. PRD §4 gives each domain its own numbered section headed
"Service". PRD §9 Phase 1 says "Core service (Customer + Product + Orchestration
as clean internal modules)". `AGENTS.md` said every module under `services/` is
an independently deployable service owning its own database.

The resolution is that §4 and §9 were never in conflict: **§4 decomposes
domains, §9 decomposes deployables.** They read as contradictory only because
one word — "service" — was doing both jobs. PRD v1.8 introduces §3.4 to separate
them, and this ADR records the packaging decision that §3.4 leaves open.

## Decision

**Customer, Product and Orchestration ship as three modules inside one
deployable, `services/core`. The Ledger remains a separate deployable.**

The modules are separated by the compiler and by the database, not by
convention:

- **Multi-module Maven build.** `core/customer`, `core/product`,
  `core/orchestration` and `core-app`, each with its own POM. Dependency
  direction is declared in the POMs, so a cycle is a build failure. A module
  cannot import another module's internals because they are not on its
  classpath.
- **One database, one schema per module** (`customer`, `product`,
  `orchestration`), with per-module Flyway locations.
- **One database role per module,** granted only on its own schema. A
  cross-module query fails at runtime, in the test suite, on first attempt —
  the same medicine `ledger_app` applies to row-level security, and for the same
  reason: a boundary that only a reviewer enforces is a boundary that erodes.
- **Cross-module calls go through published interfaces** (`CustomerEligibility`,
  `ProductDecisionService`), never through another module's repositories or
  tables.
- **Only `core/orchestration` holds the ledger client.** Enforced by the
  classpath — no other module declares the dependency — and by ArchUnit.
- **Bulkheads per module:** separate connection pools, and a separate executor
  for document-upload and search workloads, so administrative traffic cannot
  consume the resources transfers depend on.

## Rationale

### Splitting a synchronous decision path multiplies failure, it does not isolate it

Separation isolates faults when a dependency is asynchronous or optional. A
transfer is neither: before it posts a single entry it needs the customer's
status and KYC tier, the product configuration, and the applicable limits and
fees. It cannot proceed on a partial answer.

Three deployables at 99.9% each, all required, compose to roughly 99.7% — about
2.2 hours of monthly downtime against 43 minutes for one process at the same
individual availability. The ledger hop exists in either design, so the real
comparison is one network dependency against three. Shared fate beats partial
fate when the caller can do nothing useful with a partial answer.

### The limit reservation has to be one transaction

Limit enforcement must be a *reservation*, not a check: two concurrent transfers
that each observe the limit unbreached will both proceed. A reservation is only
safe if it is taken in the same transaction that creates the saga.

One process, one database: that is a local `BEGIN`/`COMMIT`. Split across
deployables, the same guarantee requires a distributed reservation protocol on
the money path. This is the decisive technical argument, and it is about
correctness rather than convenience.

### One database with three schemas — not three databases

A module owns its schema exclusively: its tables, its migrations, its role, and
no other module may read it. It does **not** own a separate database, and that
is deliberate.

Three databases inside one process would be the worst of both designs. The
central benefit of combining these domains is that the limit reservation and the
saga row commit in a single local transaction; put the modules in separate
databases and that becomes a distributed transaction again — the exact cost the
combination was meant to avoid — while delivering none of the fault isolation
that separate *processes* would have bought. It would also triple the
operational surface (three connection topologies, three backup and restore
paths, three restore drills) in exchange for nothing.

Cross-schema access within one PostgreSQL database is an ordinary local
transaction, which is precisely the enabling fact. Ownership is enforced by
privilege — each module's role holds grants on its own schema only — so
"separate databases" would buy isolation that `GRANT` already provides.

The boundary that genuinely crosses a database is Orchestration → Ledger, and it
is exactly there that idempotency keys and the unknown-outcome protocol do their
work, because no transaction can span it.

### The Ledger boundary is justified by protection, and that justification does not transfer

Orchestration cannot complete a transfer without the ledger either, so the same
availability multiplication applies there and is accepted. It is accepted
because what sits behind that boundary must never be corrupted by a defect
elsewhere, and because a minimal trust surface around the monetary authority is
worth real cost (PRD §6.2, ADR 0004).

Nothing comparable is true of Customer and Product. A product-configuration
defect taking customer lookup down with it leaves both in the same failed
request they would have been in anyway.

### Evidence is what banks audit, and evidence is per deployable

PRD §10 Journey B lists what an institution's due diligence actually asks for:
security posture and certifications, architecture review, data-residency
evidence, DR/BCP test results, penetration test reports, reference calls.
Service count is not on that list. Uptime history, restore drills and documented
failure modes are — and each of those is produced *per deployable*. Fewer
deployables means the evidence arrives sooner, not later.

### The regret is asymmetric

Modular Core → separate deployables is a rehearsed extraction once the seams
above hold: move a schema, add a controller, swap an interface for a client.
Separate deployables → merged is rare and unpleasant, and nobody plans for it.
Starting modular preserves the option; starting split spends the cost daily,
on the money path, before any evidence justifies it.

## Consequences

### Accepted costs, stated rather than elided

- **PII shares a process with transaction workflows.** Per-module database roles
  keep Orchestration's credentials off customer document tables, but a heap dump
  of Core contains customer data. If a tenant's security policy or an auditor
  requires process-level isolation of PII, that is an extraction trigger, not an
  argument this ADR anticipated away.
- **A bad Core deploy affects all three domains.** The mitigation is progressive
  rollout with health gating, which is required in any topology.
- **Every Core instance carries all three modules,** so scaling is uniform.
  Acceptable at the PRD's 200 TPS per tenant cluster; an extraction trigger well
  before it is not.

### Obligations this creates

- `AGENTS.md`, the root README, `docs/conventions/commits.md`, CODEOWNERS and the
  PR template adopt the §3.4 vocabulary and name `core/orchestration` where they
  previously named "orchestration".
- The money-path merge gate (`AGENTS.md` hard rule 7) attaches to
  `core/orchestration`, not to all of Core. A change confined to `core/customer`
  does not drag the invariant and concurrency suites.
- CI provisions a second database and the per-module roles.
- The API Gateway remains unbuilt until an external API consumer exists; edge TLS
  and token validation are gateway configuration plus the shared authorization
  library (PRD §4.7.3).

### The extraction rehearsal — a deliverable, not an intention

This decision rests entirely on the claim that extraction is cheap, and that
claim is currently untested. The ledger's own practice is the precedent: the
MVCC quiesce horizon was built and proven standalone *first*, because three
guarantees were going to rest on it.

So: **once the three modules exist and the first vertical slice works, extract
`core/customer` on a throwaway branch.** Not to ship it — to find out whether
the seams hold and what the extraction actually costs. If it takes an afternoon,
the bet is evidenced. If Orchestration turns out to be reaching into customer
tables in four places, that is learned at a cost of one day rather than one
quarter. The result is recorded in this ADR's successor or in Core's CHANGELOG.

A trigger table without a known extraction cost is not actionable.

## Extraction triggers

A module is extracted when one of these is **demonstrated**, never because a
diagram would look cleaner. In order of expected arrival:

| # | Trigger | Extract |
|---|---|---|
| 1 | PII requires a process-level security boundary — tenant policy, auditor finding, or a partner requiring isolated customer-data deployment | Customer |
| 2 | A separate team owns a domain and is blocked by coordinated releases | that domain |
| 3 | One module's load materially degrades the latency of the money path, after bulkheads | that module |
| 4 | Country-specific product complexity diverges enough that Product releases on its own cadence | Product |
| 5 | Deployments of one module repeatedly destabilize others | that module |
| 6 | Different uptime targets become contractual | the stricter module |

Customer first, Product second, is the expected order: Customer has the
strongest standalone case because it is the only module holding PII.

**At least one trigger must be mechanically observable.** Trigger 3 is the
candidate: a per-module latency and connection-pool-saturation metric, alerting
when administrative workloads breach a stated share of the transfer path's
budget. A trigger that waits for someone to consult a table is a trigger that
fires late.

## Revisiting

If the extraction rehearsal shows the seams do not hold — if moving one module
turns out to be a multi-week project — then the premise of this decision is
false and the honest correction is to split before Core grows further. That
would be a new ADR superseding this one, not an edit here.
