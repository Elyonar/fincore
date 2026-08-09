# ADR 0015 — The control plane is a deployable of its own, and provisioning a tenant is a saga

**Status:** Deferred · 2026-08-08 — see
[ADR 0016](0016-tenant-bootstrap-manifest.md)
**Deferred, not withdrawn.** ADR 0016 takes the cheap path for now: tenants are
declared in a manifest and each service seeds its own registry at startup, which
removes the saga this ADR designs because nothing coordinates. This decision
remains the destination and becomes correct when any of ADR 0016's triggers
fires — a non-engineer must onboard an institution, restarting to add a tenant
becomes unacceptable, the lifecycle (suspend, resume, offboard, audit) is needed,
or the tenant count outgrows review. Read it then; do not build it before.
**Supersedes:** nothing. Completes [ADR 0010](0010-keycloak-realm-per-tenant.md)'s
consequence — *"tenant provisioning becomes a two-part operation… and the
provisioning flow must fail loudly rather than half-succeed"* — and schedules the
tenant-admin dashboard that [ADR 0014](0014-ui-runway.md) deferred as roadmap.

**Scope note.** Per `AGENTS.md` hard rule 8 this records a structural decision
only — no credentials, no auth flows, no deployment specifics.

## Context

ADR 0010 decided that a tenant is a Keycloak realm and that *"tenants never see
Keycloak — our tenant admin dashboard drives realm provisioning through the admin
API."* That dashboard was left as roadmap. What shipped instead is
`scripts/provision-tenant.sh`, and the gap between the two is now the platform's
binding constraint: no client application can be built for a tenant that cannot
be created.

Three facts about the current state make this a decision rather than a task.

**Provisioning is deliberately unreachable, and correctly so.** All three
deployables carry a `TenantRegistry` whose `register` method is documented as
*"provisioning only, and deliberately not reachable from any request path — a
service that could enrol its own caller's tenant would be back to trusting the
token's claim, which is the thing this class exists to stop."* It is called by
tests and by the development seeder and by nothing else. Any provisioning surface
must satisfy that invariant rather than quietly relax it.

**Provisioning is already a distributed operation, and the script performs one
third of it.** A usable tenant needs a realm in Keycloak *and* a row in the
ledger's `tenants`, *and* a row in Core's `platform.tenants`, *and* a row in
`notification.tenants`. Each is an independent gate: the ledger refuses postings
for an unregistered tenant, and both Core and Notification answer 404 through a
`TenantGate` that runs before any handler. The script writes one row, into
whichever database its `PGURL` points at, and its default points at the ledger.
A tenant provisioned by the sanctioned path is therefore refused by Core on every
request. That is not a bug to patch in bash; it is the shape of the problem
telling us what it is — a multi-participant operation with a partial-failure mode,
which is the thing this codebase already knows how to build correctly.

**The control plane sits above the boundary every other service sits inside.** A
tenant-scoped token is the platform's foundational assumption: `libs/auth` refuses
a token with no tenant claim, every module role is row-level-security-restricted,
and `TenantGate` refuses tenants it has never heard of. An operator creating a
tenant has, by definition, no tenant. There is no way to express that caller
inside Core's model without cutting a hole in the gate that makes Core safe.

## Decision

### The control plane is a separate deployable

A fourth deployable, `services/platform`, owns tenant lifecycle: create, read,
list, suspend, resume, offboard, and the record of who did each and when. It is
the only component in the platform that reasons across tenants.

**Rationale.** Three properties force separation rather than a Core module:

1. **It is cross-tenant by construction, and Core is not.** Hard rule 6 requires
   `tenant_id` scoping and forced row-level security on every query in a
   tenant-scoped service. The control plane's own tables are about tenants, not
   inside them. Housing it in Core would put an unscoped, RLS-exempt surface in
   the same process as every tenant-scoped one, and the distinction would survive
   only as a convention.
2. **It holds the platform's most privileged credential.** Realm administration
   is the authority to mint identities for every institution on the platform. A
   process that serves tenant traffic must not also hold it; the blast radius of
   any flaw in the request path would otherwise include every tenant's identity.
3. **It is not a domain of banking.** ADR 0006's modular-core rule governs
   banking domains that may later extract. Tenant lifecycle is not one, and
   putting it in Core would give Core a sixth module whose extraction trigger is
   "immediately".

**The accepted cost:** a fourth deployable to build, deploy and operate, and one
more service in the compose stack. Rejected alternatives are recorded in the
service's decision log; the closest was a Core module behind a path-scoped
resolver, rejected because the isolation would have been a filter ordering rather
than a process boundary.

### The control plane never writes another service's database

Each deployable keeps sole ownership of its own tenant registry, per hard rule 5.
Registration becomes an operation each service exposes to one verified peer,
rather than a table three processes write.

The control plane therefore asks each participant to register the tenant, and
each participant decides for itself. This preserves the existing invariant — no
request path can enrol its own caller's tenant, because the caller here is a
verified peer service and never a tenant's user — while turning a set of database
credentials into an API the participants control. It also means a fifth
deployable joins the protocol by implementing an endpoint, not by handing out a
connection string.

### Provisioning is a saga, with the outcome protocol the money paths already use

Creating a tenant touches an external identity provider and three services. It
can succeed, be definitively refused, or leave the caller not knowing — the same
three-valued world Core's transfers live in, and the reason
`outcome-protocol.md` exists.

Provisioning adopts it wholesale: a caller-supplied idempotency key, a persisted
saga with a recorded decision, definite refusals compensated by rolling back the
participants already completed, and unknown outcomes left in place for a
convergence pass to resolve — never guessed at, never reported as success. A
half-provisioned tenant is exactly the failure ADR 0010 named, and the platform
already contains the only discipline that reliably prevents it.

**Consequence for the interface:** provisioning is asynchronous to its caller.
The console starts a provisioning run and observes it; it does not block on a
call that may not have an answer yet.

### The control plane authenticates its own operators, in its own realm

Platform operators live in a realm of their own, distinct from every tenant
realm, with a permission vocabulary of their own. Tokens issued there carry no
tenant claim, which is precisely why they cannot be replayed against any
tenant-facing service: `libs/auth` refuses them, and the ledger refuses them
explicitly.

`libs/auth` is not amended to make the tenant claim optional. Weakening the
platform's strongest invariant in the shared library — so that every importing
service must remember to check something the library used to guarantee — would
trade a contained problem for a distributed one. The control plane resolves its
own callers, as the ledger already does for the same reason and with the same
double-locked development path.

### The console is a client, and it is not a tenant client

The operator dashboard is a client application of the control plane exactly as
the teller app is a client application of Core: it holds a token, it calls one
origin, it has no privileges of its own. It does not share the tenant edge — the
two have different audiences, different realms and different exposure, and a
single origin serving both would make an operator surface reachable wherever a
tenant surface is reachable.

### Offboarding is not deletion

A tenant is suspended, exported, and only then closed. Deleting a realm or a
registry row is never a side effect of any other operation, and the destructive
transitions are maker-checked — the same standard the platform already applies to
reversals and product publication, applied to the one action that can end an
institution's access to its own records.

## Consequences

- **A fourth deployable** joins the reactor, CI, compose and the version guard,
  with the full scaffold obligations of `service-scaffold.md`.
- **Three services gain an internal registration surface** and must accept a
  verified peer that carries no tenant. This is a contract change in each and is
  recorded in each service's CHANGELOG, not only here — the boundary
  `design-changes.md` rule 6 draws.
- **The ledger's trusted-caller list gains a second entry.** It has held exactly
  one since jwt mode landed; the list existing already is why this is
  configuration rather than design.
- **`scripts/provision-tenant.sh` is superseded** and should be deleted rather
  than left as a second, divergent path to the same state. Its single-registry
  defect is the argument for this ADR and must not survive it.
- **The tenant lifecycle becomes explicit** — a state a tenant is *in*, not an
  inference from whether some rows exist. Everything downstream, including the
  onboarding checklist the console renders, reads that state.
- **Phase 0's client work unblocks in a defined order:** the control plane
  creates a tenant and its first administrator; that administrator configures the
  institution; only then does a teller have products to transact against.

## Revisiting

If a tenant segment is ever self-provisioned — a self-service tier for very small
cooperatives, which ADR 0010 already anticipates as a possible hybrid — the
operator-only assumption here reopens, because the caller would then be a
prospect rather than a member of staff. That is a new ADR.

If the control plane acquires responsibilities beyond tenant lifecycle — billing,
licence enforcement, cross-tenant analytics — each is its own decision. Naming
them here as *not* included is deliberate: a control plane that quietly becomes
the place cross-cutting features go is how a second monolith starts.
