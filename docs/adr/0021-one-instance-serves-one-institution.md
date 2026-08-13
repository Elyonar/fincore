# ADR 0021 — One deployed instance serves one institution

**Status:** Accepted · 2026-08-13
**Amends:** PRD §3.1 constitution rule 7 ("Multi-tenant by default, with tenant ID
enforced at the data layer; dedicated-instance option for customers demanding
isolation"), which this reverses, per the standing rule that an accepted ADR
overrides an older PRD section until the PRD is revised.
**Relates to:** [ADR 0007](0007-tenant-isolation-pattern.md) (the isolation
mechanics, which stay — with a narrower justification),
[ADR 0018](0018-first-party-identity-service.md) (which already argued from this
model without it having been decided),
[ADR 0015](0015-control-plane-and-tenant-provisioning.md) /
[ADR 0016](0016-tenant-bootstrap-manifest.md) (provisioning),
[ADR 0019](0019-tenant-scoped-service-principals.md) (unchanged by this).

**Scope note.** Per `AGENTS.md` hard rule 8 this records a structural decision
only — no deployment topology, no credentials, no infrastructure specifics.

## Context

Two load-bearing documents disagree about what this platform is, and both are
being built to.

PRD §3.1 rule 7 says **multi-tenant by default**, with a dedicated instance as
something a demanding customer buys. ADR 0018 says the opposite, and does not say
it in passing — it is one of the two arguments that retired Keycloak:

> One deployment serves one institution. The operating model is an instance per
> institution; a platform operator running many institutions runs many instances,
> and any fleet-management layer sits above them … isolation between institutions
> is not a realm boundary but a key boundary: each instance signs with its own
> keys, and a token from one instance fails verification at another outright.

Realm-per-tenant existed to give each tenant its own policy inside a *shared*
identity tier. ADR 0018 removed that tier on the grounds that there is no shared
tier. That reasoning is either correct or Keycloak was retired for a bad reason,
and it has never been written down as a decision anyone could check.

The deployed instance holds **one tenant**: one row in
`bootstrap/tenants.json`, one in each of the five registries.

### Why this has to be settled now rather than noticed later

Ambiguity here is not free, and the bill is measurable:

```
418  method signatures carrying UUID tenantId
301  tenant_id references across migrations
134  CREATE POLICY statements
100  FORCE ROW LEVEL SECURITY declarations
 68  SET LOCAL / set_config('app.tenant_id') call sites
  5  tenant registries, one per deployable
  4  byte-identical TenantGate classes
```

Neither model is wrong. Building for both is the one option that buys nothing —
the full isolation cost without the shared-tier economics that would justify it.
And the question is about to be asked concretely: the platform scaffolding that
five services currently each hold a copy of is being extracted into one place,
and it cannot be written without knowing whether tenancy is a property of a
request or of a deployment.

## Decision

**A deployed instance serves one institution. Isolation between institutions is a
process and key boundary, not a row predicate.**

Three things follow, and the second is the one most likely to be misread.

**1. The fleet lives above the platform, not inside it.** An operator running
many institutions runs many instances. Nothing inside a deployable coordinates
across institutions, no service holds a registry of them, and no API addresses
one from another. [ADR 0015](0015-control-plane-and-tenant-provisioning.md)'s
control plane stays deferred and is now deferred for a reason rather than for
now: it was designed for a shared tier that will not exist.

**2. An instance may still hold more than one tenant, and the tenant column,
claim and registry all stay.** This is not a contradiction and it is the reason
nothing is being torn out. Two cases are already in the product:

- a **sandbox beside live** — PRD §9.1 issues one on day one, with test money,
  and an institution exploring its own configuration against real balances is
  exactly the case where a row predicate earns its keep;
- an **integrator running two small institutions** on one deployment, which
  PRD §11.3 sells as a licence profile.

So `tenant_id` stays on every table, the tenant stays a token claim never
asserted by a caller, and each deployable keeps its registry — whose job is now
sharper than before: refusing a token naming a tenant *this instance does not
serve*, which in a keyed-per-instance world is a stronger control than it was.

**3. Row-level security keeps its mechanics and loses its old justification.**
[ADR 0007](0007-tenant-isolation-pattern.md)'s three layers stand unchanged and
its two hard-won findings — owner exemption without `FORCE`, session `SET`
leaking across a pooled connection — remain true and remain the reason the
mechanics are what they are. What changes is the claim made for them. RLS is no
longer "the thing that keeps two institutions apart", because two institutions
are two processes with two signing keys. It is the backstop for the narrower
cases above, and for an application bug inside one of them. That is a smaller
claim and a true one, and a security control resting on a false justification is
one nobody can reason about when it matters.

## Consequences

### What this settles

- ADR 0018's Keycloak retirement now rests on a recorded decision rather than an
  assumption, and the two documents stop disagreeing.
- The platform starter can own tenancy **once** — the identity filter, the
  registry check, the scoped connection — instead of five services each holding a
  copy. This is the immediate practical value of the decision.
- Nothing new is built for a shared tier: no cross-institution provisioning API,
  no realm mapping, no per-tenant key management inside a service.
- [ADR 0019](0019-tenant-scoped-service-principals.md) is untouched. A service
  reading tenant data still mints a principal scoped to the tenant the event
  named; that the instance usually serves one institution does not make the claim
  optional, and a token without it is refused by `libs/auth` regardless.

### What this is explicitly not

**It is not a licence to remove what exists.** No migration drops a policy, no
repository stops scoping, no `tenant_id` column is dropped. Those are 134
policies across six databases, and the reward for removing them in a platform
that already runs them correctly is a rounding error against the risk. The
saving this decision buys is in what is **not built next**, not in what is
undone.

**It is not a claim that one tenant per instance is enforced.** It is the
default and the thing designs are shaped by, not an invariant. Anything that
would break on a second tenant in one instance is still a defect.

### The cost accepted

Per-institution operational overhead: an instance, a database cluster, a
migration run and a key per institution, and no shared-tier economies at all.
That is the price of the isolation, and for institutions holding customer
deposits under their own licence it is the right side to be wrong on.

## Revisiting

This reverses if the commercial model does — specifically, if a shared tier is
sold: many small institutions on one deployment, priced on the assumption that
they share infrastructure. That is a business decision and it would arrive as
one. It would make ADR 0015's control plane immediately correct, re-open the
question ADR 0018 closed about where identity policy lives, and turn every
mechanic in ADR 0007 back into the primary control rather than the backstop.

Nothing here makes that reversal expensive, which is deliberate: the tenant
column, the claim and the registry are kept precisely so the door stays open.
