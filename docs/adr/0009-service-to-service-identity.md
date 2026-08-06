# ADR 0009 — Callers are authenticated services, and the ledger enforces its own allowlist

**Status:** Accepted · 2026-08-05
**Supersedes:** nothing.

**Scope note.** This ADR records an architectural decision and the boundary at
which it is enforced. Per `AGENTS.md` hard rule 8, it deliberately contains no
credentials, no concrete authentication flows, and no deployment
infrastructure detail; those belong with the deployment, not in a public
repository.

## Context

`AGENTS.md` hard rule 3 says only Orchestration may call the ledger's write API,
and PRD §6.2 says the ledger accepts posting requests only from Orchestration
with read-only access for Reporting. Today both are prose. The ledger's own
README lists the gap plainly: `api.md` names the allowed caller for every
endpoint and the service does not enforce any of them.

That was acceptable while no caller existed. Core is the first caller, so the
rule either becomes a mechanism now or it becomes a sentence the platform has
stated twice and implemented never — and the second outcome is worse than never
having claimed it, because reviewers and agents read it as true.

There is a second, subtler reason to settle this now. Tenant identity currently
arrives in a header. A header is a claim, not a credential, and the ledger is
explicit that this is not authentication. Any service built on top inherits that
posture unless it is fixed at the point where the second service arrives.

## Decision

**Three distinct identities travel with every money-path request, and each is
verified by a different party.**

1. **The human or system principal** — who asked. Established at the edge by the
   identity provider (Keycloak, PRD §4.10) and carried downstream as validated
   identity context.
2. **The calling service** — which system acted. Established by mutual TLS with
   per-service certificates; a service asserts its own machine identity and the
   callee verifies it.
3. **The tenant** — whose money. Derived from the validated principal's identity
   context, **never** from an unauthenticated header once identity is in place.

**Authorization is deny-by-default and enforced by the owning service.** The
gateway authenticates; it never makes business authorization decisions. Each
service holds the allowlist for its own endpoints:

- The ledger's write endpoints accept exactly one calling service identity:
  `core-orchestration`. Its read endpoints additionally accept Reporting.
  Everything else is refused.
- The allowlist is configuration, so adding a caller is a reviewable change
  rather than a code change — but it is also **asserted by tests**, because an
  allowlist nobody tests is a comment.

**Verification mechanics are shared, domain checks are not.** A single internal
authorization library validates tokens, extracts principal, tenant and
permissions, and offers the enforcement helpers. Every service imports it; no
service reimplements it. Domain-specific checks — approval tier against amount,
maker ≠ checker, state machine legality — stay in the service that owns the rule,
because only it can know them.

**The library lands in `libs/`.** Two consumers exist the moment Core is built —
Core and the ledger, which needs real authentication to retire its header
posture — so the standing rule that a library is extracted only when a second
consumer exists is satisfied, by evidence rather than by anticipation.

## Rationale

Three identities rather than one because examiners ask two different questions —
*who authorized this* and *which system performed it* — and a single collapsed
identity can answer neither properly. The ledger already models this in its data
(`initiated_by` and `executed_by` as separate columns); this ADR supplies the
mechanism that makes the second column trustworthy rather than self-asserted.

Enforcement in the owning service rather than at the gateway follows the
platform's stated split: definitions centralized, enforcement decentralized. A
gateway-only model means anything that reaches the internal network reaches the
ledger, which is the specific outcome PRD §6.2 exists to prevent.

Tenant from identity rather than from a header matters most in the multi-tenant
case the row-level security backstop was built for. A header-supplied tenant is
a caller assertion; if it is wrong or forged, every downstream isolation control
faithfully enforces the wrong boundary.

## Consequences

- Keycloak is deployed alongside Core rather than after it. PRD §4.10 already
  says identity is *set up* in days while the ledger is *developed* in months,
  and that retrofitting identity context is prohibitively costly.
- The ledger gains authentication and caller enforcement, retiring two entries
  from its known-limitations list. That is a contract change to the ledger and is
  recorded in its CHANGELOG, not only here.
- Local development and tests need a path that does not require the full identity
  stack. The requirement is that the *insecure* path be impossible to enable
  accidentally in a deployed environment and that it announce itself loudly when
  active — the same discipline the ledger's `log` event adapter follows.
- Service identities are issued and rotated per deployment. How that is done is
  an operational matter recorded with the deployment.

## Revisiting

If a service mesh is adopted (PRD §3.2 anticipates one when service count
warrants), certificate issuance and rotation move into the mesh and this ADR's
transport layer becomes mesh configuration. The three-identity model and the
enforcement-by-owner rule are independent of that and would stand unchanged.
