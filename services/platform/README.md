# Platform — the control plane

The one component that reasons across tenants, and the only path by which an
institution becomes a tenant of FinCore.

It creates the tenant's identity realm, registers the tenant with every
deployable that gates on a registry, seeds the institution's first administrator,
and holds the lifecycle state everything downstream reads. It knows a tenant's
identity and status, and nothing about what the tenant does.

**Status: design, not code.** `docs/design.md` is DRAFT. No domain code lands
while it is, per
[`service-scaffold.md`](../../docs/conventions/service-scaffold.md).

---

## Documents

| Document | What it holds |
|---|---|
| [`docs/design.md`](docs/design.md) | Scope, boundaries, decision log |
| [`docs/data-model.md`](docs/data-model.md) | Schema, constraints, the tenant lifecycle |
| [`docs/api.md`](docs/api.md) | Endpoints, permission vocabulary, error catalog |
| [`docs/provisioning-protocol.md`](docs/provisioning-protocol.md) | The saga: participants, compensation, convergence |
| [`docs/console.md`](docs/console.md) | The Next.js operator dashboard this service exists to serve |
| [`docs/testing.md`](docs/testing.md) | Suites and their status |
| [`docs/CHANGELOG.md`](docs/CHANGELOG.md) | Amendments, and the entries this design requires in other services |

Recorded by
[ADR 0015](../../docs/adr/0015-control-plane-and-tenant-provisioning.md), which
completes [ADR 0010](../../docs/adr/0010-keycloak-realm-per-tenant.md)'s
consequence and schedules the dashboard
[ADR 0014](../../docs/adr/0014-ui-runway.md) deferred.

## Boundaries

**Never** a banking domain — no customers, accounts, money or products.
**Never** a reader of tenant data: it holds no credentials to any tenant-scoped
schema and no route into one.
**Never** in a tenant's request path: if this service is down, every existing
tenant is unaffected.
**Never** a billing, licensing or support-impersonation tool. Each is a plausible
neighbour and each needs its own decision.

Full statement, with reasons: [`docs/design.md`](docs/design.md) §2.

## Why it exists

ADR 0010 recorded that provisioning a tenant is a multi-part operation and *"must
fail loudly rather than half-succeed"*. What shipped was
`scripts/provision-tenant.sh`, which creates the realm and writes **one** of the
three registry rows a tenant needs — defaulting to the ledger's. Core and
Notification each run a `TenantGate` that answers 404 for a tenant they have
never heard of, so a tenant provisioned by the sanctioned path authenticates
correctly and then 404s on every request anyone makes.

That is not a bug to patch in bash. It is a multi-participant operation with a
partial-failure mode — which this codebase already knows how to build correctly,
on the money path, and now builds once more.

## Known limitations

Stated before they are discovered, per the scaffold.

- **Realm-administration authority is deployment-supplied configuration**, the
  same posture as Core's ledger service token today, and it carries the same
  follow-up.
- **The readiness checklist is assertion-based.** This service records that an
  institution's administrator completed a setup step; it does not verify it by
  reading Core, because it must not. A tenant marked live whose administrator
  skipped a step is possible.
- **Offboarding export is not built.** `CLOSED` is reachable only from
  `SUSPENDED`; the export that should precede it is a step the operator performs
  by other means.
- **No cross-tenant reporting** beyond counts and states. Anything aggregating
  tenant business data is excluded by the boundaries above.
- **Nothing is implemented.** Every suite in `docs/testing.md` is PLANNED and
  every endpoint in `docs/api.md` is unbuilt.
