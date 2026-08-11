# Core — The Administration Surface

**Status:** AGREED v2.0 (2026-08-11) — amendments via [`CHANGELOG.md`](CHANGELOG.md)

What a tenant's own administrator needs in order to turn a provisioned tenant
into an institution that can transact. Three capabilities, designed as one batch
because they share error codes, permission checks and pagination conventions,
and because a client team cannot begin against a third of a contract.

Recorded by [ADR 0016](../../../docs/adr/0016-tenant-bootstrap-manifest.md)
(which seeds the administrator this surface exists for) and
[ADR 0017](../../../docs/adr/0017-tenant-defined-roles.md) (which decides the
permission/role split §4 implements).

**Planned rows enter `api.md` only as they are built.**
`ApiSurfaceCatalogTest` fails a documented-but-unbuilt route in both directions,
and that is a feature this document must not fight — the same rule
[`ui-runway.md`](ui-runway.md) §3 works under.

> **Amended v1.24 (2026-08-10).** §3 is built: all six product-authoring
> endpoints are served and now appear in `api.md`. Building it corrected four
> things this document committed to — the migration it said was unnecessary, the
> module in which account verification is possible, the shape of a ledger that
> cannot be asked, and five refusal reasons it did not name. Each is recorded in
> [`CHANGELOG.md`](CHANGELOG.md) 1.24.0. **§4 (account opening) remains unbuilt**,
> so the account columns a rule may name can still only hold ids obtained outside
> this platform.

---

## 1. Why this exists

A tenant seeded by ADR 0016 has a realm, three registry rows, and one
super-administrator who can sign in. That administrator can create
organizational units, tills, approval tiers and customers — and cannot make the
institution able to take a single deposit. Three gaps stand in the way, and each
is a capability the schema already models and no endpoint reaches:

1. **Nothing prices.** `POST /v1/products` accepts a code, a name and a type.
   `product.fee_rules`, `product.limit_rules` and `product.loan_rules` are fully
   modelled, constrained, and written by nothing outside the test suite. A
   published product version carries no pricing, so every money path resolves a
   version that cannot answer what a fee is.
2. **No account can be opened.** `POST /v1/customers/{id}/accounts` links a
   ledger account id the caller must already possess. Core's ledger client has
   no open operation, and clients never address the ledger (ADR 0014). So
   customer accounts, the fee-income, funding and penalty-income accounts every
   configured product refers to, and the account a till *is*, are all
   unreachable.
3. **No second user exists.** No endpoint creates a member of staff or composes
   a role, and the eight `job:*` composites are identical for every institution.

## 2. Scope

**In:** product rule authoring and version lifecycle; opening ledger accounts
through Core; user and role administration for the caller's own realm; the
`units` claim derivation that `OrgUnitController` documents and nothing performs.

**Out, deliberately:** cross-tenant anything (that is the deferred control plane,
ADR 0015); customer-facing self-service (no customer-scoped identity exists —
see the client-readiness analysis); interest rules for deposit products, which
`api.md` already defers explicitly and which this batch does not quietly adopt;
bulk import and migration tooling (PRD Phase 3).

## 3. Product authoring

### Decisions

**A version is the unit of change, and rules belong to a version.** The schema
already says this and the API has never let anyone act on it: `create()`
hardcodes `version = 1`, and a published version is immutable by trigger. So
authoring means *drafting a new version*, editing its rules while it is a draft,
and publishing it under the existing maker-checker. Editing a published
version stays impossible, which is the property the money path depends on.

**Rules are replaced as a set, per version, not patched row by row.** A fee
schedule is a coherent object; PATCHing one rule out of four invites a version
that prices half of what its author intended. The write is idempotent and total:
send the rules the version should have.

**`effective_from` becomes settable on a draft**, defaulting to publication time.
PRD §4.3 requires effective-dated configuration and the column has been present
and unreachable since V2. A version already published never moves.

**Reading rules back is a first-class endpoint, not a debugging aid.** An
administrator who cannot see the pricing of a product they published cannot
review it, and a portal cannot render an edit form without it.

### Planned rows

| Method | Path | Permission | Purpose |
|---|---|---|---|
| `POST` | `/v1/products/{id}/versions` | `products:create` | Draft version *n+1*, optionally cloned from a published version. |
| `GET` | `/v1/products/{id}/versions/{version}` | `products:read` | The version with its full rule set. |
| `PUT` | `/v1/products/{id}/versions/{version}/fee-rules` | `products:create` | Replace the fee schedule. Draft only. |
| `PUT` | `/v1/products/{id}/versions/{version}/limit-rules` | `products:create` | Replace limits, per KYC tier × channel. Draft only. |
| `PUT` | `/v1/products/{id}/versions/{version}/loan-rules` | `products:create` | Replace loan terms, including penalty and income accounts. Draft only, LOAN products only. |
| `PATCH` | `/v1/products/{id}/versions/{version}` | `products:create` | Set `effectiveFrom`. Draft only. |

`POST /v1/products/{id}/versions/{version}/publish` already exists and is
unchanged — including its database-enforced refusal of publisher == author.

### Refusals

`PRODUCT_NOT_FOUND` · `VERSION_NOT_FOUND` · `VERSION_NOT_DRAFT` (any write to a
published version) · `RULES_INVALID` with reasons `UNKNOWN_KYC_TIER`,
`UNKNOWN_CHANNEL`, `UNKNOWN_FEE_BASIS`, `UNKNOWN_SCHEDULE_KIND`,
`BOUNDS_INVERTED`, `RATE_OUT_OF_RANGE`, `ACCOUNT_NOT_FOUND`,
`ACCOUNT_WRONG_TYPE` · `LOAN_RULES_ON_NON_LOAN_PRODUCT` ·
`EFFECTIVE_FROM_IN_THE_PAST`.

> **Superseded by v1.24.** `VERSION_NOT_FOUND` duplicated the existing
> `PRODUCT_VERSION_NOT_FOUND`, which stands. Five reasons were added that this
> list did not name — `UNKNOWN_OPERATION`, `UNKNOWN_LIMIT_TYPE`,
> `AMOUNT_MALFORMED`, `CURRENCY_INVALID`, `EFFECTIVE_FROM_INVALID` — and one code,
> `LEDGER_UNREACHABLE`, for the case below. The current catalog is `api.md`'s,
> which `ErrorCodeCatalogTest` holds to the enum.

`ACCOUNT_WRONG_TYPE` matters more than it looks: a fee-income account that is
actually a customer account routes fee revenue into somebody's savings, and
nothing downstream would notice.

Verifying it means asking the ledger, which Product may not do — hard rule 3
forbids it an HTTP client, and the module graph runs Orchestration → Product,
never the reverse. The port is therefore declared on the consumer's side as
`product.api.LedgerAccounts` and implemented in Orchestration, which already
holds the only ledger client on the platform and already depends on
`product.api`. No module edge is added in either direction.

That port answers `Known`, `Absent` or `Unreadable`, never a boolean. An account
the ledger could not be asked about is `LEDGER_UNREACHABLE` and a 503, never
`ACCOUNT_NOT_FOUND` — refusing a correctly-authored rule because the ledger was
restarting is the read-side version of compensating an unknown outcome.

## 4. Account opening

### Decisions

**Core opens and links in one operation, or does neither.** Two calls would
leave an orphaned ledger account on any failure between them, with no owner and
no way to find it. This is a saga in the platform's existing sense: the ledger
call carries a derived idempotency key, and a retry converges rather than
opening a second account.

**The caller names a purpose, not a ledger account type.** `CUSTOMER`,
`FEE_INCOME`, `PENALTY_INCOME`, `FUNDING`, `TILL`, `SUSPENSE` — Core maps
purpose to the ledger's own type and to the permission required. An
administrator opening a fee-income account and a teller opening a customer
account are different acts with different authority, and a shared endpoint that
took a raw type would collapse them.

**Customer accounts require a product.** The account is opened *under* a
published product version, which is what makes a later fee or limit resolvable
and closes the gap where nothing ties an account to the product it was sold as.

**The ledger's contract is unchanged.** This adds `LedgerClient.open`; the
ledger already serves `POST /v1/accounts`, and the edge still routes nothing to
it.

### Planned rows

| Method | Path | Permission | Purpose |
|---|---|---|---|
| `POST` | `/v1/customers/{id}/accounts/open` | `customers:link` | Open a customer account under a published product version and link it. Returns the linked account. |
| `POST` | `/v1/internal-accounts` | `accounts:manage` | Open an institution account — fee income, penalty income, funding, suspense. |
| `GET` | `/v1/internal-accounts` | `accounts:read` | The institution's own accounts, by purpose. |

`POST /v1/customers/{id}/accounts` (link an existing id) is retained: migration
from another core arrives with accounts that already exist.

Two new permissions, `accounts:manage` and `accounts:read`, added to the realm
template and to `job:admin`. They are new vocabulary rather than a reuse of
`customers:link`, because opening the account that receives an institution's fee
revenue is not the same authority as attaching an account to a customer.

### Refusals

`CUSTOMER_NOT_FOUND` · `PRODUCT_VERSION_NOT_PUBLISHED` · `CURRENCY_UNKNOWN` ·
`ACCOUNT_PURPOSE_INVALID` · `LEDGER_REFUSED` (carrying the ledger's own code) ·
`LEDGER_UNREACHABLE` (503, outcome unknown — retry the same key).

The last two are the outcome protocol, unchanged: a definite refusal is terminal
for that key; an unknown is a 503 the caller must retry with the same key until
it gets a definite answer.

## 5. User and role administration

Implements ADR 0017. Permissions are the platform's closed vocabulary; roles are
the tenant's composition over it.

### Decisions

**Core administers its own realm and no other.** The tenant's existing service
client is granted realm administration scoped to that realm, so a flaw here
reaches one institution's directory and cannot reach another's.

**Role names are namespaced by the service, never by the caller.** ADR 0017's
guardrail 0: the permissions claim is a flat set of effective realm roles, so a
role named `transfers:reverse` would grant that permission invisibly. Created
roles get a `role:` prefix applied server-side, and any name colliding with the
permission catalog or a reserved prefix is refused.

**No administrator grants a permission they do not hold.** Without this, one
administration permission is equivalent to all of them.

**The last holder of user administration cannot be removed** — including by
themselves. A locked-out tenant needs an operator with platform authority, which
ADR 0016 deliberately does not provide.

**User and role changes are maker-checked**, on the existing single-use,
maker ≠ checker, database-enforced approval mechanism. PRD §4.10 requires this
and names it alongside reversals; reversals have it and this does not.

**Assigning a unit writes both records.** `OrgUnitController` documents that the
`units` claim is derived from `unit_assignments`; nothing derives it, so branch
assignment currently has no effect on authorization. Assignment becomes one
operation over Core's record and the directory attribute, and the drift stops.

### Planned rows

| Method | Path | Permission | Purpose |
|---|---|---|---|
| `GET` | `/v1/permissions` | `users:read` | The platform's permission catalog, with what each grants. Read-only, forever. |
| `POST` | `/v1/roles` | `users:manage` | Create a role over a subset of the catalog. Maker-checked. |
| `GET` | `/v1/roles` | `users:read` | The tenant's roles, template and custom, with their permissions. |
| `PUT` | `/v1/roles/{role}/permissions` | `users:manage` | Replace a role's permission set. Maker-checked. |
| `DELETE` | `/v1/roles/{role}` | `users:manage` | Remove a custom role. Refused while held; template roles are not deletable. |
| `POST` | `/v1/users` | `users:manage` | Create a member of staff with roles and units. Temporary credential, forced change. |
| `GET` | `/v1/users` | `users:read` | Staff, filtered by role and unit, keyset-paged. |
| `GET` | `/v1/users/{id}` | `users:read` | One user with roles, units and status. |
| `PUT` | `/v1/users/{id}/roles` | `users:manage` | Replace role grants. Maker-checked. |
| `PUT` | `/v1/users/{id}/units` | `users:manage` | Replace unit assignments — Core record and claim together. |
| `POST` | `/v1/users/{id}/deactivate` | `users:manage` | Maker-checked. Refused for the last administrator. |
| `POST` | `/v1/users/{id}/reactivate` | `users:manage` | Maker-checked. |

Three new permissions — `users:read`, `users:manage`, and `roles` folded into
`users:manage` deliberately rather than split, because an actor who can grant
roles to users and an actor who can define what a role means are the same threat.
Both join `job:admin` and neither joins any other composite.

### Refusals

`USER_NOT_FOUND` · `ROLE_NOT_FOUND` · `ROLE_NAME_INVALID` with reasons
`COLLIDES_WITH_PERMISSION`, `RESERVED_PREFIX`, `MALFORMED` · `ROLE_IN_USE` ·
`ROLE_NOT_CUSTOM` · `PERMISSION_UNKNOWN` · `PERMISSION_NOT_HELD_BY_GRANTOR` ·
`LAST_ADMINISTRATOR` · `UNIT_NOT_FOUND` · `APPROVAL_REQUIRED` (202) ·
`APPROVAL_INVALID` · `DIRECTORY_UNREACHABLE` (503).

**The revocation window is a documented property**, not a detail: a role change
takes effect on the affected user's next token, so a withdrawn permission
survives the remainder of the current one. Any interface that implies revocation
is instant is lying.

## 6. Conventions inherited unchanged

Deny-by-default per endpoint. Tenant from the token, never a header. Absent and
another tenant's are indistinguishable. Caller-supplied idempotency key with a
payload fingerprint on every creating operation, arbitrated by a unique index.
Keyset pagination with an opaque cursor. Money as decimal strings in responses.
Every rejection carries a documented `code`, a `reason` where one code spans
several causes, and `details` holding the facts a message interpolates.

## 7. Testing

Every suite here is **PLANNED** until it exists; only IMPLEMENTED suites gate
merges.

- **Deny-by-default probe on every new endpoint**, enumerated from the route
  list so a new endpoint without a probe fails the suite.
- **Cross-tenant invisibility** on every new read.
- **Product authoring**: a published version refuses every write; rules replace
  as a set; an unpublished version prices nothing; `effective_from` in the past
  is refused; a fee account of the wrong type is refused; version *n+1* clones
  and diverges without touching *n*.
- **Account opening**: open-and-link is atomic under a definite ledger refusal;
  a retry after an unknown outcome converges to one account rather than two;
  purpose maps to the right ledger type and the right permission.
- **Role administration**, the security core: a role name colliding with a
  permission is refused; an administrator cannot grant a permission they lack;
  the last administrator cannot be removed, including by themselves; maker ==
  checker is refused; an approval is single-use and bound to its target.
- **Units**: assigning a unit updates the Core record *and* the claim, proven by
  a token minted after the change carrying the new scope.

## 8. What this changes elsewhere

- **`keycloak/realm-template.json`** — four new permissions (`accounts:read`,
  `accounts:manage`, `users:read`, `users:manage`) added to the catalog and to
  `job:admin`; the tenant's service client granted administration of its own
  realm. The eight `job:*` composites are reframed as templates a tenant may
  copy, rename or delete, which is a documentation change and not a behavioural
  one.
- **`libs/auth`** — unchanged. ADR 0017 turns on a resolution the platform
  already performs on every request; no service's enforcement moves.
- **The ledger** — unchanged. `LedgerClient.open` calls an endpoint that has
  existed since v1.
- **The permission catalog becomes code** — a constant per permission and a
  `PermissionCatalogTest` reconciling `require` call sites against the realm
  template in both directions. Independently a hard-rule-10 fix, and a
  precondition of role authoring: guardrail 0 needs an authoritative list to
  check proposed names against.
