# Product Service

> **The catalogue, and the answer the money path needs.** What this institution
> offers, in versions; and given a product, a customer's tier, a channel and an
> amount — what does this cost, and is it allowed. It holds no money and no
> customer.

**Status: implemented, pre-1.0. 12 tests green against real PostgreSQL.**

**No `docs/design.md`, and that is deliberate.** This service was designed as a
module inside Core and is documented there —
[`services/core/docs/design.md`](../core/docs/design.md) §Product,
[`data-model.md`](../core/docs/data-model.md) and
[`api.md`](../core/docs/api.md) still hold the agreed contract. What changed is
packaging, not design, and the record of that change is
[ADR 0020](../../docs/adr/0020-customer-and-product-become-deployables.md).

---

## Memory map

| You want to know… | Read |
|---|---|
| Why this is a deployable rather than a Core module | [ADR 0020](../../docs/adr/0020-customer-and-product-become-deployables.md) |
| The agreed design it inherited | [`services/core/docs/design.md`](../core/docs/design.md) |
| How a decision reaches the money path | [`services/core/docs/saga-protocol.md`](../core/docs/saga-protocol.md) |
| How tenant isolation works here | [ADR 0007](../../docs/adr/0007-tenant-isolation-pattern.md) |
| Platform hard rules | [`AGENTS.md`](../../AGENTS.md) |

## Quick facts

| | |
|---|---|
| Language / framework | Java 25 LTS, Spring Boot |
| Storage | PostgreSQL, own database, one `product` schema |
| Tables | `products`, `product_versions`, `fee_rules`, `limit_rules`, `product_types`, `tenants` |
| Database role | `product_app` for traffic; migrations run as the owner |
| Money representation | integer minor units, always. No floats, anywhere |
| Calls out to | **nothing.** It is called; it calls nobody |
| Events | publishes none |
| Port | 8085 in the compose network, unpublished on the host — reached through the edge at `/api/product/` |

## What it owns

**The catalogue and its versions.** A product is a name and a code; a *version*
is what actually prices anything. Versions are drafted, authored, published and
scheduled — an account records the version it was opened under, so changing
tomorrow's prices never restates yesterday's transactions.

**Fee rules.** What a deposit, withdrawal or transfer costs: flat, percentage, or
percentage with a cap, and which internal account the fee is credited to.

**Limit rules.** The ceiling on what a customer of a given tier may move through
a given channel, per transaction and per day.

**The types this institution offers.** `SAVINGS` and `CURRENT` were a `CHECK`
constraint, so a fixed deposit needed a migration and a release to name — a
deployment to add a word. Nothing in the evaluator branches on the type; it picks
an icon and groups a list, which makes it the institution's vocabulary rather
than the platform's.

## The two guarantees that matter

**A published version is immutable, and the database enforces it.** Three
triggers — `product_versions_are_immutable_once_published`,
`fee_rules_are_immutable_once_published`,
`limit_rules_are_immutable_once_published` — refuse the update rather than
trusting every caller to check first. This is the reason the service could be
extracted at all: the guarantee lives where the data lives, so it survives a
caller that is now on the other side of an HTTP boundary.

**The evaluator denies by default.** A product with no per-transaction limit rule
refuses everything. An omission must never read as permission — the alternative
is an unpriced product quietly moving money at no cost and no ceiling, which is
the failure nobody notices until reconciliation.

## What it deliberately does not own

- **Any customer.** It is told a tier; it never looks one up. The tier's
  vocabulary belongs to the Customer service, and Core validates a limit rule's
  tier against that list before writing it — a rule naming a tier nobody can hold
  stores cleanly and then never matches, which reads as a configured ceiling and
  behaves as a blanket refusal.
- **Any money.** It decides what a movement should cost. Moving it is the
  ledger's, through Core.
- **Which account is charged.** It names an internal account code; Core resolves
  it against the institution's own register.

## What is a constant here, and why

Fee kinds, limit types and channels are **not** tenant vocabulary, unlike product
types and KYC tiers. Each is a branch in a program: a new row would be a value
the evaluator silently ignores. A dropdown offering something the engine will not
honour is worse than a short dropdown, because it fails at transaction time
instead of at configuration time.

## Run

```bash
# whole stack
docker compose up --build

# this service's suite, against its own database
./mvnw -pl services/product verify
```

The suite runs against real PostgreSQL and never an in-memory substitute — the
two immutability triggers are the whole reason this service could be extracted,
and a substitute that did not enforce them would make the suite agree with a
claim the database is the only thing actually keeping.

## Known limitations

- **No design doc of its own.** It inherits Core's, which still describes it as a
  module. Worth splitting when this service's contract next moves; a fresh
  document now would be a second description of an unchanged design.
- **No error-catalog test.** The codes this service returns are still listed in
  Core's `api.md`, which is the catalog until the surfaces are split properly.
