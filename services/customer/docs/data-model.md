# Customer — Data Model

**Status:** AGREED v1.0 (2026-08-13) — amendments via [`CHANGELOG.md`](CHANGELOG.md).

One database (`customer`), one schema (`customer`), one role. Migrations run as
the owner; traffic connects as `customer_app` — `NOSUPERUSER`, `NOCREATEDB`,
`NOCREATEROLE`, **`NOBYPASSRLS`**, DML only. PostgreSQL skips row-level security
entirely for a `SUPERUSER` or `BYPASSRLS` role, leaving every policy inert while
the catalog still reports it enabled — and this is the schema where that would
matter most.

No worker role: nothing here is claimed from a queue or drained in the
background. This service answers questions about people.

## Tables

| Table | Holds |
|---|---|
| `customers` | The profile: name, phone, email, locale, status, KYC tier, and `external_ref` — the institution's own customer number, unique within the tenant |
| `customer_accounts` | Which ledger accounts a person holds, with the account number, currency, role and the product governing it. `unlinked_at` retires a link rather than deleting it |
| `customer_tier_changes` | Every KYC tier change, with its reason and who made it. **Append-only** |
| `communication_consent` | The current consent decision, per category and per channel |
| `consent_changes` | Every consent decision ever made. **Append-only** |
| `kyc_tiers` | The institution's own tier vocabulary, rather than a platform enum |
| `numbering` | The customer and account numbering series: prefix, width, next value |
| `tenants` | The registry. A token naming a tenant absent from here answers 404 |

## Append-only history, enforced by trigger

| Trigger | On | Refuses |
|---|---|---|
| `customer_tier_changes_are_append_only` | `customer_tier_changes` | Any `UPDATE` or `DELETE` |
| `consent_changes_no_update` | `consent_changes` | Any `UPDATE` or `DELETE` |

Both are triggers rather than conventions for the same reason the ledger's
entries are append-only: these are the rows an institution shows a regulator, and
a history that can be rewritten is not one. The current-state tables
(`customers.kyc_tier`, `communication_consent`) are projections of these; the
history is the record.

A tier change and its audit row commit together or not at all. A tier that moved
without a trace is the failure this pair exists to prevent.

## Tenancy

Every tenant-scoped table has row-level security **enabled and `FORCE`d**, with a
policy comparing `tenant_id` to `customer.current_tenant()`. `FORCE` matters
because PostgreSQL exempts a table's owner from its own policies otherwise, and
migrations run as the owner.

Tenant context is set with `SET LOCAL` inside the request transaction, never a
session-level `SET`: a session variable survives the connection's return to the
pool and is then read by a request for a different tenant. Under
[ADR 0021](../../../docs/adr/0021-one-instance-serves-one-institution.md) an
instance normally serves one institution, so this is a backstop rather than the
primary control — but it is the backstop for the sandbox-beside-live case, and
this is the schema where getting it wrong discloses personal data.

## PII

Every column that identifies a person lives here and nowhere else on the
platform. Two things are true and worth stating plainly rather than discovering:

- **Contact details are stored in plaintext.** The notification service encrypts
  its own copy of an address; this one does not. The asymmetry is recorded in
  [`design.md`](design.md) as a gap, not a decision.
- **There is no erasure path.** A customer cannot be closed, merged or deleted,
  and the reason is unsettled rather than unbuilt: ledger entries reference
  account holders, and what erasure means against an immutable book is a question
  nobody has answered.

## Migrations

| Version | What |
|---|---|
| `V1__baseline` | Customers, accounts, consent, tier history, numbering, the append-only triggers, RLS |
| `V2__tenant_registry` | The registry, so an unknown tenant 404s rather than seeing an empty register |
| `V3__kyc_tiers` | Tiers become the institution's own vocabulary, not a platform enum |

Append-only: an applied migration is never edited, and a checksum mismatch is a
build failure rather than something to repair away.
