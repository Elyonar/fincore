# ADR 0007 — Tenant isolation is a platform pattern, not a per-service invention

**Status:** Accepted · 2026-08-05
**Supersedes:** nothing. Promotes a pattern proven in the ledger to platform law.

## Context

Constitution rule 7 and `AGENTS.md` hard rule 6 both require `tenant_id` scoping
on every query. Neither says *how*, and "scope every query" is advice that holds
right up until someone forgets — which is the case the rule exists for.

Building the ledger produced the mechanism, and produced it the hard way. Two
findings from that work are recorded in the ledger's design changelog and are
the reason this ADR exists:

- **PostgreSQL exempts a table's owner from its own row-level security policies**
  unless the table is additionally marked `FORCE ROW LEVEL SECURITY`, and exempts
  a `SUPERUSER` or `BYPASSRLS` role unconditionally. With the service connecting
  as the bootstrap superuser, every policy was inert while `pg_class` still
  reported row-level security as enabled. The guarantee failed silently and
  survived inspection.
- **A session-scoped `SET` of the tenant variable leaks across pooled
  connections.** It passes every single-tenant test and fails only when a
  connection is returned to the pool and borrowed by a request for a different
  tenant — which is precisely the scenario the backstop exists to cover.

Both are properties of PostgreSQL, not of the ledger. Every service that stores
tenant data will meet them. Recording the mechanism only inside
`services/ledger/docs/architecture.md` means the next service either
rediscovers it, or ships without it and reports itself protected.

Core is the second service and holds customer PII. This is the moment to
promote the pattern.

## Decision

**Every service storing tenant-scoped data implements the same three-layer
isolation, with the same mechanics.**

### Layer 1 — application scoping

Every query filters by tenant. This is the layer that does the work in normal
operation and the layer that will eventually have a bug in it. The other two
exist because of that certainty.

### Layer 2 — structural impossibility where a foreign key can carry it

Composite foreign keys on `(tenant_id, id)` rather than `(id)`, so a
cross-tenant reference cannot be expressed. Where an identifier is not covered by
a foreign key — a group or cohort label, for example — that exception is stated
explicitly in the service's data model and carries its own test.

### Layer 3 — row-level security as backstop

Non-negotiable mechanics, all four:

1. Row-level security **enabled and `FORCE`d** on every tenant-scoped table.
2. The service connects as a **restricted role** — `NOSUPERUSER`, `NOBYPASSRLS`,
   DML privileges only. Migrations run as the schema owner. DDL and traffic are
   different jobs and must not share an identity.
3. Tenant context is set with **`SET LOCAL` inside the request transaction**,
   never a session-level `SET`. Handing a connection back to the pool outside a
   transaction is forbidden for any tenant-scoped query.
4. A **schema-presence test suite** asserts each policy exists *and fires*, that
   tables are `FORCE`d, and that the connecting role is neither superuser nor
   `BYPASSRLS`. "Enabled" and "enforced" are different claims and only the
   second one matters.

### In a multi-module deployable, add privilege separation

Where several modules share a database ([ADR 0006](0006-modular-core.md)), each
module connects as **its own role, granted only on its own schema**. Tenant
isolation and module isolation are different concerns and both are enforced by
the database rather than by review.

### A tenant registry is required before a tenant can hold data

Row-level security isolates tenants from each other; it has nothing to say about
whether a tenant is *real*. Without a registry, any well-formed identifier
produces a working, empty, silently-provisioned tenant. Each service either
holds a registry or validates against the one that owns tenant lifecycle.

## Rationale

Isolation that a reviewer enforces is isolation that erodes; isolation the
database enforces is isolation that holds when the application is wrong. The
ledger's experience is the argument: the failure mode here is not a visible
error but a green build reporting a protection that is not in force.

The pattern is also cheap to adopt and expensive to retrofit. Adding
`tenant_id`, policies and a restricted role to a schema with production data in
it is a migration nobody wants to write.

## Consequences

- Every service ships a restricted application role and runs migrations as the
  owner; local development, CI and production all provision it.
- CI provisions the roles for every service, not just the ledger.
- The schema-presence suite is part of the definition of done for any service
  with tenant data — see [`../conventions/service-scaffold.md`](../conventions/service-scaffold.md).
- Services with no tenant-scoped data are exempt from layers 2 and 3 and must say
  so explicitly in their design, rather than being silently uncovered.

## Revisiting

If a service arrives whose storage is not PostgreSQL, layer 3 needs an
equivalent in that engine, or the service must justify operating with layers 1
and 2 only. That justification belongs in a new ADR, not in a service design.
