# ADR 0016 — Tenants are declared in a manifest and seeded at startup; the control plane waits

**Status:** Accepted · 2026-08-08 · **implemented** — `bootstrap/tenants.json` and
`bootstrap/seed-registries.sh`, which registers every tenant with all five
deployables that gate on a registry
**Relates to:** defers [ADR 0015](0015-control-plane-and-tenant-provisioning.md),
which stays on record as the destination. Implements
[ADR 0010](0010-keycloak-realm-per-tenant.md)'s provisioning consequence by the
cheapest means that is actually correct.

**Scope note.** Per `AGENTS.md` hard rule 8 this records a structural decision
only — no credentials, no auth flows, no deployment specifics. The manifest
carries *references* to secrets and never secrets, which is part of the decision
rather than an implementation note.

## Context

ADR 0015 designs a control plane: a fourth deployable, its own database, a
provisioning saga with compensation and convergence, an operator realm, and a
dashboard. It is the right destination and it is a quarter of work before the
first institution exists.

The immediate need is smaller and sharper. FinCore is deployed; a handful of
institutions must exist in it; each needs one super-administrator who can log in
and configure their own bank. Nobody is onboarding banks hourly, and the people
doing it are the engineers who run the deployment.

Two facts make the cheap path viable rather than merely tempting.

**The pattern already exists and is already correct.** Every deployable carries a
`DevTenantSeeder` — an `ApplicationRunner`, `@ConditionalOnProperty`, calling an
idempotent `register` that ends in `ON CONFLICT (id) DO NOTHING`. It registers
exactly one hardcoded tenant and announces itself as development-only. The
distance between that and "register the tenants named in a manifest" is a list
and a sanctioned profile.

**Keycloak already imports realms from files, including their users.** The
compose stack runs `start-dev --import-realm` against a mounted directory today,
and the reason recorded in `compose.yaml` is exactly the property wanted here:
*"the realm is rebuilt from the file each time and cannot drift from what the
repository says it is."*

So the expensive part of ADR 0015 — a saga coordinating four participants
through an unknown-outcome protocol — exists to solve a problem that a manifest
does not have. A saga is needed when one actor drives several others through a
single operation that must not half-succeed. A manifest inverts the arrangement:
each participant reads the same declaration and converges to it independently, on
its own schedule, idempotently, on every boot. Nothing coordinates, so nothing
half-coordinates.

## Decision

### Tenants are declared, not created

One manifest — an ordered list of institutions with the minimum a tenant needs to
exist — is the single source of truth for which tenants this deployment serves.
Its schema, and the rules below as obligations, live in
[`tenant-bootstrap.md`](../conventions/tenant-bootstrap.md).

The tenant identifier is chosen in the manifest and used unchanged everywhere:
the three registries, the realm's tenant claim, every token. The current defect —
a provisioning script that writes one registry while two others go unwritten — is
structurally impossible when all three read the same line of the same file.

### Each service seeds its own registry, at startup, from the manifest

`DevTenantSeeder` becomes `TenantSeeder`: same shape, a list instead of a
constant, and a sanctioned deployment path instead of a development-only one.

This preserves hard rule 5 exactly — a deployable owns its database, and no
process writes another's. It also means **convergence is free**. A service that
was down when the manifest changed seeds itself when it next boots. A service
whose database was restored re-seeds on start. There is no in-flight state, no
compensation, and no indeterminate outcome, because there is no distributed
operation — only three independent readers of one declaration.

The accepted cost is that consistency is eventual rather than transactional: for
the duration of a rollout, one service may know a tenant that another does not.
Bounded by startup, visible in readiness, and — for a tenant nobody has logged
into yet — without consequence.

### The realm, and the first administrator, are rendered from the same manifest

The existing realm template is rendered once per manifest entry into the identity
provider's import directory, each rendering carrying that tenant's identifiers,
its client origin, and **its first super-administrator**.

A realm with no users is the defect in the current script: provisioning reports
success and nobody can log in. A tenant is not provisioned until a human can sign
in, and that human's account is part of the declaration rather than a follow-up
task.

The first administrator is created with a temporary credential and a forced
change on first use. The manifest names where that credential comes from; it
never contains one.

### Seeding is additive, and removal is never deprovisioning

Adding an entry provisions. Removing one does nothing at all.

Stated as a decision because the opposite is the obvious implementation and it is
catastrophic: a declarative seeder that reconciles by deleting turns an editing
mistake into an institution losing its realm, its registry rows, and its access
to its own records. Deprovisioning is a deliberate, separately-authorized act and
is not available here in any form.

### A malformed manifest refuses to start

Validated as a whole before anything is written. A service that seeds the valid
half of a broken list produces exactly the partially-provisioned tenant this ADR
exists to prevent — the same failure as the script, arrived at by a different
route.

### The control plane is deferred, with a stated trigger

ADR 0015 is not withdrawn and its design is not deleted. It becomes correct when
any of these is true, and each is a reason a manifest stops being adequate rather
than a matter of taste:

- **A non-engineer must onboard an institution.** A manifest requires
  repository access and a deployment; the moment onboarding belongs to someone
  without both, it needs an interface.
- **Restarting to add a tenant becomes unacceptable.** Import-on-start is fine
  while restarts are routine and cheap.
- **The lifecycle is needed** — suspend, resume, offboard, an audit trail of who
  did what. A manifest declares that a tenant exists; it cannot express that one
  was suspended on a date by a named person.
- **Tenant count outgrows review.** A list a human reads is a control; a list
  nobody reads is a configuration file that nobody checks.

## Consequences

- **No new deployable.** No fourth database, no operator realm, no saga engine,
  no control-plane UI. The `services/platform` design stays on record as
  DEFERRED, which is what `design-changes.md` rule 4 requires of a decision that
  is replaced rather than abandoned.
- **Three services gain a real seeder** and a CHANGELOG entry each, in place of
  a development-only one. MINOR: capability added, nothing weakened.
- **`scripts/provision-tenant.sh` is deleted.** Its single-registry defect is the
  argument for this ADR and must not survive it. Two paths to the same state
  means one is wrong and nobody knows which.
- **The manifest becomes a reviewed artefact.** Adding an institution is a pull
  request, which is a weaker control than an audit trail and a stronger one than
  a shell command nobody logged.
- **This unblocks a login screen, not a working bank.** The seeded administrator
  can create organizational units, tills and approval tiers, and cannot create a
  product that prices anything, open the internal accounts every money path
  requires, or create a second member of staff. Those three gaps are unaffected
  by this decision and are the actual remaining work; they are named in
  [`tenant-bootstrap.md`](../conventions/tenant-bootstrap.md) §6 so that nobody
  reads this ADR and concludes otherwise.

## Revisiting

The triggers above are the revisit conditions, and ADR 0015 is the answer when
one fires. Adding lifecycle operations, an audit trail, or an onboarding UI to
the manifest path is not an option worth taking: those are the control plane,
and building them incrementally into a startup seeder produces the control plane
without its boundaries.
