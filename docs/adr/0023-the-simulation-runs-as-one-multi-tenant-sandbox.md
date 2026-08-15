# ADR 0023 — The simulation runs as one multi-tenant sandbox instance

**Status:** Accepted · 2026-08-13
**Relates to:** [ADR 0021](0021-one-instance-serves-one-institution.md) (which
this exercises rather than reverses), [ADR 0007](0007-tenant-isolation-pattern.md)
(the mechanics this leans on), [ADR 0015](0015-control-plane-and-tenant-provisioning.md)
/ [ADR 0016](0016-tenant-bootstrap-manifest.md) (provisioning, which gains a
second path), [ADR 0018](0018-first-party-identity-service.md) (the login path
this changes), [ADR 0019](0019-tenant-scoped-service-principals.md) (unchanged).

**Scope note.** Per `AGENTS.md` hard rule 8 this records a structural decision
only — no deployment topology, no credentials, no infrastructure specifics.

## Context

A banking-ecosystem simulation is being built above this platform: many
simulated institutions on one canvas, each with branches, staff and customers,
agents transacting through the real APIs, transfers crossing a switch, and a
live event feed animating it. The simulation layer is a separate system that
talks to FineCore only through its APIs.

Dropping a bank on that canvas should take seconds. ADR 0021's fleet model makes
it take an instance: six services, six logical databases and edge configuration
per institution. That is right for institutions holding real deposits under
their own licence, and absurd for a canvas.

So the simulation appears to want the model ADR 0021 just decided against, one
day after it was decided. It does not, and the distinction is the whole content
of this ADR.

### Why this is not a reversal

ADR 0021 kept the tenancy machinery deliberately and said what it was kept for:

> An instance may still hold more than one tenant, and the tenant column, claim
> and registry all stay. … a **sandbox beside live** — PRD §9.1 issues one on day
> one, with test money …

and set a standard that this document is written against:

> It is not a claim that one tenant per instance is enforced. It is the default
> and the thing designs are shaped by, not an invariant. **Anything that would
> break on a second tenant in one instance is still a defect.**

The data plane already meets that standard. Every table is tenant-scoped and
RLS-forced, `TenantGate` is per-request, each registry takes N rows, and the
batch jobs iterate tenants. The tenant-scoped service-principal path already
mints working tokens for *any* active tenant — `ServiceClients` checks
`activeTenants()`, not the instance tenant.

One path does not: human login. `Tenants.instanceTenant()` refuses to choose
when more than one tenant is active, `StartupSummary` makes that fatal at boot,
and `LoginRequest` carries no tenant discriminator at all, so row-level security
then hides every other institution's users. By ADR 0021's own standard that is a
defect, and it is one whether or not a simulation is ever built.

## Decision

**The simulation runs as one multi-tenant sandbox instance — the case ADR 0021
reserves — and the work it requires is framed as fixing the defects a second
tenant exposes, not as re-opening the deployment model.**

Three things follow.

**1. Production posture is untouched.** One instance per real institution,
isolation as a process and key boundary, the fleet above the platform. Nothing
in this ADR is an argument for putting two licensed institutions on one
deployment, and the sandbox instance is not a shared tier: it holds no
institution's real deposits, and the commercial reversal ADR 0021 names has not
happened.

**2. Login gains a tenant discriminator, because it always should have had
one.** The bootstrap manifest already validates a `realm` per tenant, checks it
for duplicates — and then discards it; `auth.tenants` has no column for it. That
field is persisted and login is keyed on `(realm, username)`. The ambiguity
throw goes, because an instance that knows which institution a login names has
nothing to be ambiguous about. The data model needs no other change: `auth.users`
is already keyed by tenant.

**3. Runtime provisioning becomes a second path, beside the manifest, not
instead of it.** ADR 0016's manifest stays the production path. Registration
over HTTP is added as a peer-authenticated surface per registry — the shape
[ADR 0015](0015-control-plane-and-tenant-provisioning.md) already anticipated
when it said participants expose a registration surface — driven by a
provisioning orchestration that fans out to all six registries and creates the
institution's first administrator. A partial fan-out is the failure mode that
matters: a tenant that authenticates and then 404s everywhere. The orchestration
owns that, and the manifest path is unaffected by its existence.

This does **not** resurrect ADR 0015's control plane. There is no
cross-institution registry, no realm mapping service, no API that addresses one
institution from another. Each service exposes registration for itself and knows
nothing of the others.

## Consequences

### What this buys

- The canvas gesture "drop a bank" becomes one call, and the simulation stops
  being gated on a deployment topology it cannot afford.
- Three defects get fixed that were defects before the simulation existed: login
  that cannot name its institution, starter vocabularies seeded by migration
  against the tenants that happened to exist at migration time, and a tenant
  profile that is validated and thrown away.
- The sandbox case ADR 0021 reserved gets exercised on purpose rather than
  discovered by accident the first time PRD §9.1's sandbox is issued.

### What it costs

- A second provisioning path to keep correct, and the fan-out orchestration that
  makes it atomic enough to trust. The manifest was one file and one boot; this
  is six registries and a failure mode.
- The login path grows a discriminator that a single-tenant deployment does not
  need, and every client sends it. This is accepted: a field whose value is
  constant in production is cheaper than a code path that only works when the
  answer is unique.
- RLS moves back toward being a primary control **in the sandbox instance
  specifically**, which ADR 0021 named as exactly the case where a row predicate
  earns its keep. The claim made for it in production is unchanged.

### What this is explicitly not

It is not permission to run two licensed institutions on one deployment. It is
not a shared identity tier. It is not ADR 0015's control plane arriving by the
back door — and the test of that is concrete: no service gains knowledge of any
institution but the ones in its own registry.

## Revisiting

This ADR is about a sandbox. If the simulation instance ever holds money anyone
can lose, it stops being one and ADR 0021's fleet model applies to it like
anything else.

The provisioning surface outlives the simulation regardless: the sandbox PRD
§9.1 promises needs it, and so does any institution that wants a second
environment. If a shared tier is ever sold, this surface is the part of ADR
0015's control plane that would already exist.
