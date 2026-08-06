# Commit Convention — the holy grail of history

**Status:** Standing convention. Every commit on `main` follows this. The git
log is public documentation: it tells the story of the build (and feeds
changelogs, videos, and AI-agent context) — treat it with the same care as
code.

## Format

```
<type>(<scope>): <subject>

<body — the why, wrapped at 72 chars>

<footer — breaking changes, references>
```

Only `<type>(<scope>): <subject>` is mandatory. Subject: imperative mood
("add", not "added"/"adds"), no trailing period, ≤ 72 chars.
One logical change per commit — never mix a feature and a refactor.

## Types

| Type | Use for |
|---|---|
| `feat` | new capability visible to a service's consumers |
| `fix` | bug fix |
| `test` | adding/strengthening tests only (no production-code change) |
| `docs` | documentation only (READMEs, service docs, ADRs) |
| `refactor` | code change that alters no behaviour |
| `perf` | performance improvement, behaviour preserved |
| `build` | build system, dependencies, Maven config |
| `ci` | CI workflows |
| `chore` | repo housekeeping that fits nothing above |
| `revert` | reverts a previous commit (reference it in the footer) |

## Scopes

The scope names the module or area touched:

| Scope | Meaning |
|---|---|
| `ledger` | services/ledger |
| `core/orchestration` · `core/product` · `core/customer` | the modules of services/core — **one scope per module, not one per deployable**, because the money-path merge gate attaches to `core/orchestration` alone and the scope is how a reviewer knows whether it applies |
| `core` | services/core structure itself: the aggregator build, wiring, cross-module scaffolding |
| `libs` | shared libraries (or the lib name once several exist) |
| `adr` | architecture decision records |
| `docs` | cross-cutting docs (service docs use the service's scope) |
| `repo` | root-level structure, conventions, licensing, community files |
| `deps` | dependency bumps (with `build`) |

Cross-cutting commit touching several services: pick the dominant scope, or
`repo` if truly global. If you can't pick one scope, the commit is probably
too big — split it.

## Body (when the subject isn't enough)

Explain **why**, not what — the diff shows what. Reference the design doc or
ADR that justifies the change. For money-touching code, state which test
suites cover it.

## Footer

- `BREAKING CHANGE: <description>` — plus `!` after the scope in the header
  (`feat(ledger)!: …`). Breaking a published API contract requires an ADR or
  design-doc change in the same PR.
- `Refs: #123` / `Closes: #123` — issue links.
- `ADR: 0005` — when the commit implements a recorded decision.

## Examples

```
feat(ledger): enforce per-currency balance in posting validation

Transactions must balance within each currency independently (PRD §3.1);
a mixed-currency transaction balancing only in aggregate is rejected.
Covered by invariant + property suites.

ADR: 0004
```

```
test(ledger): concurrency hammer for simultaneous postings to one account
```

```
docs(adr): 0005 - balance sign convention (credit-positive)
```

```
fix(ledger)!: reject zero-amount entries at API boundary

BREAKING CHANGE: POST /v1/transactions now returns 422 for amountMinor = 0;
previously accepted and posted a no-op entry.
```

```
build(deps): bump spring-boot-starter-parent 4.1.0 -> 4.1.1
```

## Extra rules for money-touching scopes (`ledger`, later `orchestration`)

1. No `feat`/`fix` commit without its tests in the same commit (or the same
   PR with the test commit adjacent). The suites gate the merge anyway; the
   history should show code and proof together.
2. A commit that changes posting, balance, hold, or reversal behaviour must
   name the invariant(s) it touches in the body.

## Local setup (one-time)

```bash
git config commit.template .gitmessage
```

Now `git commit` (without `-m`) opens with the template as a guide.

## Enforcement

Manual discipline + PR review for now. When outside contributions start, a
commitlint CI check enforces the header format mechanically (tracked as a
future `ci` task) — convention first, tooling when it earns its keep.
