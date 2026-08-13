# ADR 0010 — One Keycloak realm per tenant, and identity lands before Core

**Status:** Superseded · 2026-08-05 — by
[ADR 0018](0018-first-party-identity-service.md) (2026-08-09): identity is a
first-party service and Keycloak is retired. Nothing in the platform runs
Keycloak any more. What survived is the *shape* this ADR argued for — one tenant
boundary per institution, tokens as the only bridge, enforcement staying in the
owning service — which is why the swap changed the issuer and very little else.
**Supersedes:** nothing. Implements PRD §4.10 and completes
[ADR 0009](0009-service-to-service-identity.md).

**Scope note.** Per `AGENTS.md` hard rule 8 this records a structural decision
only — no flows, no credentials, no deployment specifics.

## Context

[ADR 0009](0009-service-to-service-identity.md) decided that the tenant comes
from a validated token rather than a header, and that a shared authorization
library carries the mechanics. It did not decide how tenants map onto the
identity provider, and that mapping is not reversible cheaply: it determines
where users live, how policies are scoped, and what a tenant migration costs.

Core cannot be built without the answer. Its design assumes tenant-from-token in
every endpoint, every saga attribution record, and every row-level security
context call.

## Decision

### One realm per tenant

Each tenant is a Keycloak realm. Staff, tellers, agents and API consumers for
that institution live in that realm; the realm issues their tokens; the token
carries the tenant identifier as a claim.

**Rationale.** PRD §4.10 requires per-tenant password, MFA and session policies,
and per-tenant federation for institutions with existing directories. A single
shared realm cannot express those — policy in Keycloak is realm-scoped, so one
realm means one policy for every institution on the platform. The requirement
decides the shape.

Realm-per-tenant also gives a clean answer to two questions that get expensive
later: a tenant's users are exported or deleted as a unit, and a tenant moving to
a dedicated instance (PRD §3.2) takes its realm with it.

**The accepted cost:** realm count grows with tenants, and Keycloak's per-realm
memory and startup overhead makes this a bounded strategy rather than an
unlimited one. The addressable Nigerian market is roughly 800 microfinance
banks, which is within range but not comfortably so.

**Revisit trigger, stated now so it is not discovered late:** if realm count
passes the point where startup time or memory forces vertical scaling of the
identity tier, the correction is realm sharding across Keycloak clusters — not a
collapse into a shared realm, which would forfeit the per-tenant policies this
decision exists to provide.

### Tenants never see Keycloak

Our tenant admin dashboard drives realm provisioning through the admin API.
Keycloak is infrastructure, not a product surface.

### Identity lands before Core's first endpoint

Sequencing, in order:

1. Keycloak deployed, first realm, starter permission vocabulary and role
   templates
2. `libs/auth` — token validation, identity context extraction, `require`
   helpers, and context propagation
3. Core's first endpoint

The library is written against the identity *interface*, not against Keycloak.
A development resolver may stand in while the realm model is being provisioned,
subject to the same rule ADR 0009 sets for any insecure path: it must be
impossible to enable accidentally in a deployed environment, and it must
announce itself loudly at startup — the discipline the ledger's `log` event
adapter already follows.

### The ledger adopts it too

The ledger currently accepts a tenant header, which it documents as not being
authentication. Once `libs/auth` exists the ledger consumes it, and that header
path is removed. This is a change to the ledger's contract and is recorded in
its CHANGELOG, not only here.

## Consequences

- `libs/` gains its first library. Two consumers exist — Core and the ledger — so
  the standing rule that a library is extracted only when a second consumer
  exists is satisfied by evidence rather than anticipation.
- Tenant provisioning becomes a two-part operation: a realm in Keycloak and a
  row in the platform tenant registry. Neither alone makes a usable tenant, and
  the provisioning flow must fail loudly rather than half-succeed.
- Local development and CI need an identity path that requires no running
  Keycloak, under the constraint above.
- Per-tenant policy configuration becomes a tenant-admin product surface, which
  is roadmap work the PRD already anticipates.

## Revisiting

If a tenant segment emerges that needs no per-institution policy — a
self-service tier for very small cooperatives, say — a shared realm for that
segment alongside dedicated realms for the rest is a reasonable hybrid. That
would be a new ADR; it is not a licence to drift into one realm by default.
