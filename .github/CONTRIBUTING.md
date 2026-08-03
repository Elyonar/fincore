# Contributing to fincore

Thank you for your interest. A few ground rules keep this project trustworthy —
it is banking software, and the bar is correctness first.

## Before you write code

1. **Read the design doc** for the service you're touching (`docs/design/`).
   Behaviour that contradicts an agreed design needs a design discussion first,
   not a PR.
2. **Significant decisions get an ADR** (`docs/adr/`). Small fixes don't.

## Contributor License Agreement (CLA)

Every contribution requires a signed [CLA](CLA.md) assigning contribution rights
to the project maintainers. This is non-negotiable and enforced from the very
first external contribution — it is what allows the project to remain
sustainably licensed (AGPL public license, commercial licenses funding
maintenance). By opening a pull request you will be asked to sign via the CLA
bot.

## Correctness rules for ledger-adjacent code

- Money amounts are **integer minor units** (kobo/cents). A `double` or `float`
  touching a money value fails review, no exceptions.
- Ledger entries are **append-only**. Corrections are reversing entries.
- Every posting path must be **idempotent** and covered by the invariant,
  property-based, and concurrency test suites. No merge to the ledger with any
  of those suites red.
- **Never publish security-sensitive specifics** in code comments, docs, or
  issues: real auth flows, fraud thresholds, or infrastructure details of any
  deployment.

## Commits

Every commit follows the convention in
[`docs/conventions/commits.md`](../docs/conventions/commits.md) —
`<type>(<scope>): <subject>`, imperative mood, one logical change per commit,
money-touching commits ship with their tests. Run
`git config commit.template .gitmessage` once to get the template locally.

## Style

- Java 25, Spring Boot. Follow the existing module layout.
- Tests are documentation: name them after the guarantee they prove.
