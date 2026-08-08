# ADR 0013 — Lending starts as a Core module, above Orchestration

**Status:** Accepted · 2026-08-08
**Supersedes:** nothing. Applies ADR 0006's packaging logic to the next domain,
and amends the module dependency order it froze.

## Context

Lending is the next domain the roadmap needs (PRD §4.5, Phase 0's "basic loan
flow", Phase 3's "Lending service extracted"). The PRD names it a domain in its
own right, with its own events and its own eventual deployable — and the word
*extracted* in Phase 3 quietly presumes it starts somewhere else first. That
presumption deserves a decision rather than an inheritance.

Two facts shape it:

1. **Lending's every money movement is an Orchestration saga.** A disbursement
   is a transfer with a schedule attached; a repayment is a transfer with an
   allocation attached; an accrual recognition is a posting. Lending decides
   *what* should move and *why*; it must never gain its own path to the Ledger,
   because hard rule 3 ("only core-orchestration calls the Ledger's write API")
   is the single most load-bearing sentence in `AGENTS.md`.

2. **Lending's reads are Core's modules.** Eligibility is Customer's answer,
   loan-product pricing and penalty rules are Product's, and PAR-by-branch is
   the Organization module's dimension. As a separate deployable, every one of
   those becomes a network call with an outcome protocol; as a module, they are
   the same in-process ports Orchestration already consumes.

The counter-case for a separate deployable — independent scale, independent
deploy cadence, a separate team — describes a stage the platform has not
reached: one team, no pilot tenant yet, and a compose file with three
deployables already. ADR 0006 chose modules for exactly this stage and priced
the escape hatch: one schema, one role, one migration history per module, so
extraction is a move, not a rewrite.

## Decision

**Lending is Core's fifth domain module** — schema `lending`, role
`core_lending`, own Flyway history, own outbox, the full ADR 0006/0007
machinery — designed in `services/core/docs/lending.md` and agreed before
implementation like every other design on this platform.

**The dependency order gains a level.** Until now the rule was "orchestration
is the top: it asks Customer and Product, nothing asks it." Lending sits above
it:

```
lending → orchestration → { customer, product, organization }
        ↘ customer, product, organization (reads)
```

Lending consumes Orchestration's *published* transfer surface (its `api`
package — the same boundary HTTP callers use, minus the network) and never its
internals, never its tables, and never the Ledger. `ModuleBoundaryTest`'s
"nothing depends on orchestration" rule is amended to "nothing but lending and
app", and a new rule pins the inverse: orchestration must never know lending
exists, or a cycle makes both unextractable.

**Extraction triggers, named now** so the move happens on evidence rather than
on fatigue — any one of these reopens the packaging question *before* the work
that needs it starts:

- group lending lands (its write patterns — group schedules, joint-liability
  allocation — are heavy enough to want their own scaling);
- a credit-bureau connector goes live (Lending gains an outbound network
  dependency with its own failure modes, which is deployable-shaped);
- a second team forms around lending, or a tenant needs lending released on a
  cadence Core's money path should not share.

**What Lending does not absorb**, recorded with the same firmness as ADR 0012's
exclusions: interest and penalty *rules* stay Product configuration (Lending
evaluates and applies; it never owns pricing); disbursement and repayment
*postings* stay Orchestration sagas; delinquency *reporting formats* belong to
Compliance & Reporting when it exists — Lending owns classification facts, not
regulator renditions.

## Consequences

- Phase 0's demo loan flow rides Core's existing runway: same deployable, same
  database, same compose stack, no new service to operate before a pilot exists.
- The interest-accrual machinery Core's design deferred ("a separate body of
  work") lands inside Lending's design, where its scheduler and its value-dated
  postings have an owner.
- One more module means one more schema, role, datasource, Flyway history and
  ArchUnit boundary — the priced cost of ADR 0006, paid a third time knowingly.
- The `services/lending` directory the PRD sketches appears at extraction, not
  before; the design doc is written so that move renames packages and rehomes a
  schema rather than rethinking a boundary.
