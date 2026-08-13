# Product — Data Model

**Status:** AGREED v1.0 (2026-08-13) — amendments via [`CHANGELOG.md`](CHANGELOG.md).

One database (`product`), one schema (`product`), one role. Migrations run as the
owner; traffic connects as `product_app` — `NOSUPERUSER`, `NOCREATEDB`,
`NOCREATEROLE`, **`NOBYPASSRLS`**, DML only. The last of those is not decoration:
PostgreSQL skips row-level security entirely for a `SUPERUSER` or a `BYPASSRLS`
role, leaving every policy inert while the catalog still reports it enabled.

There is no worker role, because there is no background work here. Nothing is
claimed from a queue and nothing is drained.

## Tables

| Table | Holds |
|---|---|
| `products` | One row per product: code, name, type. The code is permanent — reusing one would make an old account's terms ambiguous |
| `product_versions` | A version of a product's configuration, `DRAFT` or `PUBLISHED`, with its author, its publisher and its effective date |
| `fee_rules` | Per version: operation, basis (`FLAT` or `PERCENT`), amount or basis points, currency, and the account the fee credits |
| `limit_rules` | Per version: operation, KYC tier, channel, currency, per-transaction and daily ceilings |
| `product_types` | The institution's own product vocabulary, rather than a platform enum |
| `tenants` | The registry. A token naming a tenant absent from here answers 404 |

## Immutability, enforced by trigger

| Trigger | On | Refuses |
|---|---|---|
| `product_versions_are_immutable_once_published` | `product_versions` | Any `UPDATE` or `DELETE` of a published version |
| `fee_rules_are_immutable_once_published` | `fee_rules` | Any `INSERT`, `UPDATE` or `DELETE` against a published version |
| `limit_rules_are_immutable_once_published` | `limit_rules` | Same |

Insert is refused as well as update, which is the case worth naming: without it,
a rule could be slid into a version *after* the checker read it and before it took
effect, which is maker-checker defeated without editing a single existing row.

This is what lets a completed transaction stay explicable. The saga records the
version that priced it; if that version could change, the record would name a
configuration that no longer exists.

## Tenancy

Every tenant-scoped table has row-level security **enabled and `FORCE`d**, with a
policy comparing `tenant_id` to `product.current_tenant()` in both `USING` and
`WITH CHECK` — read and write. `FORCE` matters because PostgreSQL exempts a
table's owner from its own policies otherwise, and migrations run as the owner.

Tenant context is set with `SET LOCAL` inside the request transaction, never a
session-level `SET`: a session variable survives the connection's return to the
pool and is then read by a request for a different tenant. That failure passes
every single-tenant test and appears only under concurrency, which is exactly the
scenario the backstop exists for.

## Money and percentages

Integer minor units throughout. A `PERCENT` fee is stored as integer basis points
— 250 is 2.50% — because a percentage applied to money is a money calculation.
No `float`, `double` or `BigDecimal` appears anywhere in this service, and
`BoundaryTest` fails the build if one is introduced.

## Migrations

| Version | What |
|---|---|
| `V1__baseline` | Products, versions, fee and limit rules, the immutability triggers, RLS |
| `V2__rules_are_per_currency` | A rule is scoped to a currency; pricing in one does not imply pricing in another |
| `V3__tenant_registry` | The registry, so an unknown tenant 404s rather than seeing an empty catalogue |
| `V4__tiers_and_types_are_tenant_vocabulary` | Product types become the institution's own, not a platform enum |

Append-only: an applied migration is never edited, and a checksum mismatch is a
build failure rather than something to repair away.
