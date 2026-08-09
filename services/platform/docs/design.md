# Platform — Design

**Status:** DEFERRED v1.0 (2026-08-08) — amendments via [`CHANGELOG.md`](CHANGELOG.md)

> **Not being built.**
> [ADR 0016](../../../docs/adr/0016-tenant-bootstrap-manifest.md) takes the cheap
> path: tenants are declared in `bootstrap/tenants.json` and each deployable
> seeds its own registry at startup, per
> [`tenant-bootstrap.md`](../../../docs/conventions/tenant-bootstrap.md). That
> removes the saga this design exists for — nothing coordinates, so nothing
> half-coordinates.
>
> The design is kept whole rather than deleted because it is the destination, and
> because the reasoning about cross-tenant boundaries, participant contracts and
> three-valued outcomes is what makes the manifest's limits legible. Build it when
> one of ADR 0016's triggers fires. No code lands from this document until its
> status changes.

The control plane: the one component that reasons across tenants, and the only
one that can bring a tenant into existence. Recorded by
[ADR 0015](../../../docs/adr/0015-control-plane-and-tenant-provisioning.md).

| Document | What it holds |
|---|---|
| `design.md` (this file) | Scope, boundaries, decision log |
| [`data-model.md`](data-model.md) | Schema, constraints, lifecycle states |
| [`api.md`](api.md) | Endpoints, permissions, error catalog |
| [`provisioning-protocol.md`](provisioning-protocol.md) | The saga, its participants, compensation and convergence |
| [`console.md`](console.md) | The operator dashboard this service exists to serve |
| [`testing.md`](testing.md) | Suites and their status |

---

## 1. What this service is

An institution becomes a tenant of FinCore through exactly one path, and this
service is it. It creates the tenant's realm, registers the tenant with every
deployable that gates on a registry, seeds the institution's first administrator,
and records the lifecycle state that everything downstream reads.

It exists because the alternative is a shell script, and a shell script cannot
express the one property that matters here: a tenant is either fully provisioned
or not provisioned at all. Half a tenant is worse than none — it authenticates,
it passes every isolation test, and it 404s on the first request anybody makes.

## 2. What this service is never

Stated as prohibitions, because a control plane is where cross-cutting features
go to accumulate and the boundary has to be written down before the pressure
arrives.

- **Never a banking domain.** No customers, no accounts, no money, no products.
  It knows a tenant's identity and status and nothing about what the tenant does.
- **Never a reader of tenant data.** It holds no credentials to any tenant-scoped
  schema and no route into one. An operator wanting to see a bank's customers has
  to be a user of that bank, which is a deliberate friction and not an oversight.
- **Never in the request path of a tenant.** No money movement, no login, and no
  read a teller performs depends on this service being up. It provisions and then
  gets out of the way; if it is down, every existing tenant is unaffected.
- **Never a billing or licensing engine.** Both are plausible neighbours and
  neither is here. Each needs its own decision.
- **Never a second identity provider.** It drives realm administration; it does
  not issue tenant tokens, hold tenant users, or participate in a tenant login.

## 3. Boundaries

**Upward, to the console.** One HTTP surface under `/v1`, operator tokens from
the platform realm, deny-by-default per endpoint. The console holds no privilege
of its own.

**Sideways, to the participants.** The ledger, Core and Notification each expose
one internal registration surface. This service calls them; they decide. It never
holds a connection to their databases — the participant list is configuration,
and a new participant joins by implementing the contract in
[`provisioning-protocol.md`](provisioning-protocol.md).

**Outward, to the identity provider.** Realm creation and administration through
the provider's admin API, driven from a versioned realm template held in
`keycloak/`. How that authority is granted, stored and rotated belongs to the
deployment and is deliberately not described here (`AGENTS.md` hard rule 8).

**Downward, to its own database.** Its own deployable, its own database, per hard
rule 5. Migrations as owner, traffic as a restricted role, per the scaffold.

## 4. The shape of the thing

A tenant has a lifecycle, and the whole design follows from making that lifecycle
explicit rather than inferring it from whether some rows happen to exist.

```
  DRAFT ──▶ PROVISIONING ──▶ PROVISIONED ──▶ CONFIGURING ──▶ LIVE
              │                                                │
              ├──▶ FAILED (compensated, may be retried)        │
              │                                                ▼
              └──▶ INDETERMINATE (converging)              SUSPENDED
                                                               │
                                                               ▼
                                                            CLOSED
```

- **DRAFT** — the operator has described the institution; nothing external exists
  yet. Editable and discardable at no cost.
- **PROVISIONING** — a run is in flight. Realm, participants and first
  administrator are being created under one idempotency key.
- **FAILED** — a participant definitively refused. Everything already created was
  compensated; the tenant is back to a clean DRAFT-equivalent and the reason is
  recorded. Retryable.
- **INDETERMINATE** — a participant did not answer. Nothing is rolled back and
  nothing is claimed; a convergence pass re-drives the same key until the answer
  is definite. This state is the reason the console polls rather than blocks.
- **PROVISIONED** — realm exists, every participant has registered the tenant,
  the first administrator can log in. The bank has an empty, working system.
- **CONFIGURING** — the institution's own administrator is setting it up: org
  units, staff, products, internal accounts, tills. This service tracks the
  checklist's completion but performs none of it.
- **LIVE** — the readiness checklist is satisfied. The first transaction may be
  taken.
- **SUSPENDED** — access withdrawn, data retained. Reversible.
- **CLOSED** — terminal, after export. Never reached as a side effect of anything.

`CONFIGURING → LIVE` is the join between this service and Core. The checklist is
a set of assertions about the tenant's configuration, and each is answered by the
tenant's own administrator completing a step — not by this service reading Core's
tables, which it must not do. See [`data-model.md`](data-model.md) §4.

## 5. Decision log

| # | Decision | Resolution | Rationale |
|---|---|---|---|
| 1 | Separate deployable, or a Core module? | **Separate deployable** | Cross-tenant by construction, holds realm-administration authority, and is not a banking domain. A module would need a hole in the gate that makes Core safe. Recorded in ADR 0015. |
| 2 | Write the participants' registries directly, or ask them? | **Ask them** | Hard rule 5: a deployable owns its database. Direct writes need three sets of owner credentials, break schema ownership, and make a fifth deployable a credential-distribution problem rather than an interface one. |
| 3 | Amend `libs/auth` for tenant-less callers, or resolve our own? | **Resolve our own** | Making the tenant claim optional in the shared library weakens the platform's strongest invariant for every importing service, converting a guarantee into something each call site must remember. The ledger set this precedent deliberately; we follow it. |
| 4 | Synchronous provisioning, or a saga? | **Saga** | Four participants including an external system. The three-valued outcome is real, not theoretical, and `outcome-protocol.md` already solves it. A synchronous call would have to either lie about unknown outcomes or hang. |
| 5 | Compensate on definite failure, or leave partial state for an operator? | **Compensate** | A half-created tenant authenticates and 404s — the failure mode ADR 0010 named. Leaving it for a human means the failure is discovered by a customer. |
| 6 | Roll back on an unknown outcome too? | **Never** | Compensating an operation that may have succeeded is how a provisioned tenant gets its realm deleted underneath it. Unknown converges; only definite refusal compensates. |
| 7 | Who creates the tenant's first administrator? | **This service, as the last step of the saga** | A realm with no users is the current defect: provisioning "succeeds" and nobody can log in. The run is not complete until someone can. |
| 8 | Does the console see tenant business data? | **No** | An operator surface that can read customer records is a cross-tenant data path with a UI. Support access to a tenant's data is a separate decision with a consent story, and is not this. |
| 9 | Is deletion available? | **Not as an operation** | Suspend, export, close. `CLOSED` retains the record; purging is a deliberate, separately-authorized data action and not a button on a dashboard. |
| 10 | Maker-checker on what? | **Suspend, resume, close** | The platform maker-checks reversals and product publication. Withdrawing an institution's access is at least as consequential. Creation is single-actor: it grants nothing that suspension cannot withdraw. |
| 11 | Idempotency key: caller-supplied or generated? | **Caller-supplied**, per the scaffold | A generated key makes a retried POST a second tenant. The console supplies one per provisioning attempt and reuses it across retries — the same contract every money-writing operation already has. |
| 12 | Realm template: in this service, or in `keycloak/`? | **`keycloak/`** | It is already there, already versioned, and already the thing `provision-tenant.sh` renders. A copy inside this service would be a second source of truth for the permission vocabulary. |
| 13 | Does this service store the tenant's client secret? | **No** | It generates, delivers once to the operator, and retains only a fingerprint sufficient to prove which secret is current. Storing it would make this database the highest-value target on the platform. Rotation re-runs the same path. |
| 14 | Tenant configuration rows in participants (e.g. the ledger's `tenant_config`)? | **Not provisioned** | The ledger falls back to platform defaults when no configuration row matches, so an absent row is a working tenant on default settings. Provisioning writes registry rows only; per-tenant tuning is a later, explicit act. |
| 15 | Is `scripts/provision-tenant.sh` kept as a fallback? | **Deleted with the implementation PR** | Two divergent paths to the same state means one of them is wrong and nobody knows which. Its single-registry defect is the argument for this service. |

## 6. What this changes elsewhere

The implementation PR's cross-service checklist. Each item is a contract change
in the named service and carries its own CHANGELOG entry there, per
`design-changes.md` rule 6.

- **Ledger** — an internal registration surface accepting a verified peer that
  carries no tenant; `platform` added to the trusted-caller list. MINOR: a
  capability is added, no existing guarantee moves.
- **Core** — the same surface; `/internal/**` handled by a service-caller
  resolver and excluded from the user-facing identity filter, so a tenant-less
  peer is authenticated without the user path ever accepting one. `TenantGate`
  must not run on it — the endpoint's whole purpose is the tenant not existing
  yet. MINOR.
- **Notification** — the same surface, same reasoning. MINOR.
- **`keycloak/`** — a platform realm alongside the tenant template; the tenant
  template gains nothing, because its permission vocabulary is already complete.
- **`scripts/`** — `provision-tenant.sh` deleted.
- **Compose / CI** — a fourth deployable, its database and restricted role, its
  place in the version guard, and an integration lane that provisions a throwaway
  tenant end-to-end against a real identity provider. That lane is the first
  thing in this repo that would have caught the single-registry defect.

## 7. Known limitations, stated before they are discovered

- **Realm-administration authority is deployment-supplied configuration.** The
  same posture Core's ledger service token has today, and it carries the same
  follow-up.
- **The readiness checklist is assertion-based.** This service records that a
  step was completed; it does not verify it by reading Core, because it must not.
  A tenant marked LIVE whose administrator lied to the checklist is possible, and
  is a product problem rather than a security one.
- **No cross-tenant reporting.** How many tenants, in what state, provisioned
  when — that is the extent of it. Anything aggregating tenant business data is
  out of scope by §2.
- **Offboarding export is not built in v1.** `CLOSED` is reachable only from
  `SUSPENDED`, and the export that should precede it is recorded as a required
  step the operator performs by other means. Naming it here rather than implying
  the transition is safe.
