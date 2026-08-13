# ADR 0017 — Permissions are the platform's vocabulary; roles are the tenant's sentence

**Status:** Accepted · 2026-08-08 · **implemented** — the permission catalog is
platform code in the identity service, roles are tenant-composed through
`/v1/roles`, and a grant beyond the granter's own access is refused
**Implements:** PRD §4.9's split — *"Centralized definitions: the master
permission vocabulary, role templates per segment … and role assignments live
here. Enforcement is decentralized to owning services"* — and PRD §4.10's
requirement that user and role changes are maker-checked.
**Relates to:** [ADR 0010](0010-keycloak-realm-per-tenant.md) (realm per tenant),
[ADR 0016](0016-tenant-bootstrap-manifest.md) (the seeded super-administrator
this ADR gives something to do).

**Scope note.** Per `AGENTS.md` hard rule 8, structural decisions only — no
credentials, no auth flows, no deployment specifics.

## Context

ADR 0016 seeds each institution with one super-administrator. That administrator
can create organizational units, tills, approval tiers and customers — and cannot
create a second member of staff, because no user-administration endpoint exists
anywhere in the platform. The eight `job:*` composites are baked into the realm
template, identical for every tenant, and a tenant has no way to alter them.

An institution's role structure is not the platform's business to fix. A
cooperative's treasurer is not a microfinance bank's supervisor; a solo lender
needs one person holding everything; a payment service bank's agent supervisor
has authority no branch teller should. Shipping eight roles and calling them
universal is a product decision nobody made deliberately.

But the opposite — letting a tenant define permissions — is meaningless rather
than dangerous, and the reason is worth stating precisely. A permission is a
string a service checks: `Authorization.require("customers:read")`. There are 35
of them, and enforcement is deny-by-default, so a tenant that invents
`loans:superapprove` has created a role nobody checks, which grants exactly
nothing. **The permission set is not a policy choice. It is the list of things
the code is capable of enforcing.**

So the customisation question has two answers, and conflating them produces
either a rigid product or a security theatre.

**A second, unrelated gap closes with the same build, and is worth naming because
it is live today.** `OrgUnitController` documents that *"assignments recorded here
are the system of record; enforcement reads the token's `units` claim, which
identity provisioning derives from these rows."* Nothing derives it.
`POST /v1/org-units/{id}/assignments` writes Core's `unit_assignments` and stops;
the `units` user attribute exists only where a realm file set it by hand. So
assigning a teller to a branch through the API has no effect on authorization,
and the two stores drift from the first assignment onward. The mechanism this ADR
introduces — Core administering its own realm — is exactly what makes that
documented derivation real, and the implementation should close it in the same
pass rather than leave a described behaviour unbuilt.

## Decision

### Permissions are platform vocabulary, closed to tenants

The 35 permissions are defined by the code that enforces them. They are read-only
to every tenant, exposed as a catalog so an administrator composing a role can
see what exists and what each one grants.

**They become an enumerable, tested catalog first.** Today they are string
literals at every call site, with no constant and no catalog test — which
contradicts hard rule 10, and means the endpoint that would serve the catalog has
no authoritative source to read. Today the realm template and the code agree
exactly: 35 permissions, zero drift in either direction. That is currently true
and structurally unguarded, and it stops being merely tidy the moment a tenant
composes roles against the list — a permission that exists in the realm and is
checked by nothing becomes a grant that appears to do something.

The platform already applies this discipline where drift would hurt:
`ApiSurfaceCatalogTest` reconciles `api.md` against the route list in both
directions, and `ErrorCodeCatalogTest` does the same for error codes. A
`PermissionCatalogTest` reconciling the code's `require` calls against the realm
template is the same medicine at the third place that needs it, and it is a
precondition of this ADR rather than a consequence.

### Roles are tenant composition, and are fully customisable

A tenant defines its own roles as named bundles of platform permissions, creates
users, and assigns roles and organizational units to them. The eight `job:*`
composites stop being the product and become what PRD §4.9 already calls them:
**templates** — a starting set a tenant copies, renames, splits or ignores.

### Role composition stays in the identity provider

A tenant's roles are realm roles composed of permission roles; assignment is a
realm-role grant. FinCore administers this through the provider's admin API,
scoped to the caller's own realm.

**Rationale.** The alternative — a `roles` table in Core, with authorization
resolved from the database — would move authorization off the token and onto a
per-request lookup, and every service would need it. Today a permission check is
a set membership test against a claim in a locally-verified token, which is what
makes identity "critical infrastructure rather than a per-transaction dependency"
(PRD §6.1): if the provider is briefly down, issued tokens keep working and money
keeps moving. A database-backed permission model forfeits that for every service
in order to give one screen a nicer implementation.

**The token model already supports this, unchanged.** The `permissions` claim is
produced by a realm-role mapper that emits the user's *effective* realm roles,
with composites resolved transitively. The proof is in the shipped dev realm: the
user `ada` holds exactly one realm role, `job:teller`, and
`CashController` requires `cash:transact` — a permission she holds only as a
composite of that role. Every money path in the jwt end-to-end suite runs on
that resolution.

So a tenant-defined role is not a new mechanism, it is the existing one used by a
different author: create a composite realm role, add permission roles to it,
assign it. `libs/auth` does not change, no service changes, the mapper does not
change, and the new role's permissions arrive in the claim on the user's next
token. **What is missing is only the API that performs those three steps.**

**The accepted cost, stated because it is a real property and not a detail:**
a role change takes effect when the affected user's next token is issued, not
immediately. A withdrawn permission survives for the remainder of that user's
current token lifetime. Whether that window is acceptable is a per-deployment
decision about token lifespan; what is not acceptable is a product that implies
revocation is instant. The administration surface says what it does.

### Administration authority is scoped to one realm, never the platform

The tenant's own service client — which already exists with a service account and
holds no realm-administration authority today — is granted administration of
**its own realm only**. A flaw in this surface reaches one institution's user
directory. It cannot reach another tenant's, because the credential has no
authority there and the realm boundary is the provider's own.

This is the same containment argument ADR 0015 makes for keeping
platform-wide realm authority out of a service that serves tenant traffic. The
distinction is that per-realm authority is exactly as wide as the tenant whose
request it serves, which is the definition of correctly scoped.

### Five guardrails, without which this surface is a privilege escalation

Each is a rule the implementation owes a test.

0. **A role name may never be, or become, a permission name.** The claim is a
   flat set of effective realm roles, and a permission check is set membership
   against it — so the role's *own name* travels in the claim beside the
   permissions it resolves to. An administrator permitted to name a role
   `transfers:reverse` would grant that permission to every holder of the role
   without the role containing it, and no review of the role's contents would
   show it. Tenant-created roles are therefore namespaced (`role:` and nothing
   else), the namespace is applied by the service rather than supplied by the
   caller, and creation refuses any name that collides with the permission
   catalog or with a reserved prefix. This is numbered zero because it is the one
   failure that is invisible in the data an administrator is looking at.

1. **No administrator may grant a permission they do not themselves hold.**
   Without this, one user-administration permission is equivalent to every
   permission on the platform: an administrator grants themselves a new role
   containing everything, and the deny-by-default model has been defeated from
   inside. This is the single most important rule in this ADR.
2. **Self-lockout is refused.** The last holder of user administration cannot be
   removed, demoted, or deactivated — including by themselves. A tenant that
   locks itself out needs an operator with platform authority, which ADR 0016
   deliberately does not provide.
3. **User and role changes are maker-checked.** PRD §4.10 requires it explicitly
   and lists it alongside reversals; reversals have it and this does not. Role
   creation, permission changes to a role, role grants, and deactivation each
   need a second signature, on the existing single-use, maker ≠ checker,
   database-enforced pattern rather than a new one.
4. **Every change is attributed and append-only.** Who granted which permission
   to whom, and when, is the first thing an examiner asks and the first thing a
   compromised account edits.

### Roles seeded by the manifest are ordinary roles

ADR 0016's `staff` list creates users holding template roles. Once this ADR
lands, the seeded super-administrator may rename those roles, change their
permissions, or delete them. Nothing in the manifest is privileged after
provisioning — it is a starting position, not a permanent structure.

## Consequences

- **Core gains a user and role administration surface**, and with it a dependency
  on the identity provider's admin API for its own realm. A service that was
  purely a consumer of tokens now also administers the directory that issues
  them; that is a boundary change and is recorded in Core's CHANGELOG.
- **The permission catalog becomes code**, with a constant per permission and a
  catalog test reconciling code against realm. Independently valuable: it closes
  a hard-rule-10 violation, makes the 35 permissions documentable, and is what
  guardrail 0 checks a proposed role name against.
- **No change to `libs/auth`, the mapper, or any service's enforcement.** The
  whole of this ADR is an administration surface over a resolution the platform
  already performs on every request.
- **`job:*` composites are reframed as templates** in the realm template and in
  the documentation. No behaviour changes; the eight roles a tenant starts with
  are the eight it has today.
- **Maker-checker coverage grows from two actions to a category.** The mechanism
  exists and is schema-enforced; this extends it to the actions PRD §4.10 names
  and the platform has not yet covered.
- **The revocation window becomes a documented property** of the platform rather
  than an unstated one.
- **The identity provider's database becomes a system of record, and must be
  operated as one.** This is the largest operational consequence and it is easy
  to miss. Today the provider holds no state worth keeping: compose runs it in
  development mode with an in-memory database and no volume, and the realm is
  rebuilt from a committed file on every start — the property `compose.yaml`
  records as *"cannot drift from what the repository says it is."* A
  tenant-authored role exists in no file and in no FinCore table; it lives only
  in the provider's own storage. The moment this ADR ships, three things follow:
  the provider needs persistent storage in every environment where roles are
  authored; it joins the ledger and Core as a database whose loss is
  unrecoverable; and it needs a backup and restore drill, which today exists only
  for the ledger and is scoped to it by name.
- **The "rebuilt from a file" property is forfeited wherever roles are authored,
  and kept where they are not.** Development may keep importing a committed realm
  and discarding runtime changes, because there is nothing there worth keeping.
  Any environment a tenant administers cannot, because discarding runtime changes
  would discard the tenant's own role structure. That asymmetry is deliberate and
  should be stated in the deployment documentation rather than discovered from a
  restart.
- **ADR 0016's manifest and this ADR pull in opposite directions, and the
  resolution is already recorded.** The manifest declares a tenant's *initial*
  state; after provisioning, the realm is live state the tenant owns. The import
  semantics `tenant-bootstrap.md` §4 already specifies — absent realms are
  imported, existing realms are left alone — are what keeps a redeploy from
  overwriting authored roles. That rule stops being a convenience and becomes a
  data-loss guard.

## Revisiting

If a tenant ever needs a permission the platform does not have, that is a request
for a capability in the service that would enforce it — not a request for a
custom permission. The answer is a new permission in the vocabulary, in a
release, checked by code. A surface that lets tenants mint permission strings
should never be built, because the string is not the grant; the check is.

If per-permission delegation becomes necessary — an administrator who may grant
only within a subset — rule 1 generalizes rather than changes: an administrator
grants from what they hold, and a narrower administrator holds less.
