# Product — Design

**Status:** AGREED v1.0 (2026-08-13) — implemented from here; amendments via
[`CHANGELOG.md`](CHANGELOG.md).

**Inherited, not invented.** This service was designed and built as a module
inside Core, and the argument for its domain rules is
[`services/core/docs/design.md`](../../core/docs/design.md) §Product — still the
record of *why* pricing works the way it does. What changed is packaging, and
that decision is
[ADR 0020](../../../docs/adr/0020-customer-and-product-become-deployables.md).

This document exists because the packaging change made Core's design doc the
wrong home for amendments: a change to this service's contract recorded in
another service's changelog is a change nobody looking here will find. v1.0 is
therefore the design as extracted — no new decisions, one new owner.

---

## What this service is for

An institution's catalogue, in versions, and the one question the money path
asks of it:

> Given a product, a customer's KYC tier, a channel, an amount and a currency —
> is this permitted, what does it cost, and under which configuration version?

It holds no money, no balances and no customer. It returns **decisions, never
postings**. A service that could post would be a second writer to the money path
(`AGENTS.md` hard rule 3), and the first symptom would be an entry nobody could
attribute.

## The decisions that shape it

**A published version is immutable, and the database enforces it.**
`reject_published_edit` refuses any update or delete of a published
`product_versions` row; `reject_published_rule_write` refuses any insert, update
or delete of a fee or limit rule belonging to one. This is a trigger rather than
a convention because a completed transaction records the version that priced it,
and "signed-off configuration stays reconstructible" is worth nothing if a
`UPDATE` can quietly rewrite history. A change is a **new version**, never an
edit.

This property is also what made this service the first extraction: a decision is
a pure function of frozen configuration, so it is safe to answer across a network
and safe for a caller to cache by `(productId, version)`.

**Publishing carries two names and they must differ.** The version records who
drafted it and who published it, and the publisher may not be the author.
Maker-checker on pricing is enforced by the row rather than by Orchestration's
approvals table, because this service may not depend on that one. A single person
who can both write and make live a fee schedule is a single person who can change
what an institution charges, unreviewed.

**Percentages are integer basis points.** A percentage applied to money is a
money calculation, so `AGENTS.md` hard rule 1 applies: 250 means 2.50%, and no
float, double or `BigDecimal` touches it. `BoundaryTest` fails the build if one
appears.

**A refusal is an answer, not an error.** `POST /v1/decisions/evaluate` returns
`200` with `permitted: false` and a reason code when a product forbids an
operation. A `4xx` would make an ordinary business outcome indistinguishable from
a malformed request, and the caller — Core, on the transfer path — needs to tell
those apart to decide whether to retry.

**Absent pricing in a currency is a refusal, not free.** A version pricing a
transfer in naira and asked about one in dollars has an answer, and it is
`CURRENCY_MISMATCH`. Reading "no rule" as "no fee" is a silent undercharge across
every second currency until somebody notices.

**Denial by default.** A version with no per-transaction limit rule refuses every
transaction under it. This is deliberate and it is why publishing an unpriced
version is not neutral — the authoring surfaces warn before the signature, not
after.

## What it talks to, and what it may not

| Direction | Who | Why |
|---|---|---|
| Inbound | Core (money path), staff via the edge | Pricing decisions; catalogue authoring |
| Outbound | **Ledger**, read-only — `GET /v1/accounts/{id}` | A fee rule names the account its fee credits; the account is verified to exist and be of the right type before the rule is stored |
| Outbound | **Identity** — `POST /v1/auth/token` | Its own service credential for the ledger read ([ADR 0019](../../../docs/adr/0019-tenant-scoped-service-principals.md)) |

**It never calls Core.** Core calls this service; the reverse would be a cycle on
the money path, and the first symptom would be a transfer that cannot complete
because two services are each waiting on the other. Enforced by the POM and by
`BoundaryTest` ([ADR 0020](../../../docs/adr/0020-customer-and-product-become-deployables.md),
obligation 3).

The `LedgerAccounts` inversion that existed inside Core is gone. Product declared
that port and Orchestration implemented it purely because a Core module was
forbidden an HTTP client; a deployable is allowed one.

## Tenancy

One deployed instance serves one institution
([ADR 0021](../../../docs/adr/0021-one-instance-serves-one-institution.md)), and
this service still carries the full three-layer pattern of
[ADR 0007](../../../docs/adr/0007-tenant-isolation-pattern.md): every query
scoped, row-level security enabled and `FORCE`d on every tenant table, traffic as
the restricted `product_app` role, tenant context set with `SET LOCAL` inside the
request transaction.

A tenant registry (`product.tenants`) gates every request. A token naming a
tenant this instance does not serve answers **404, not 403** — telling a caller
which tenant ids exist is an enumeration oracle. Before the registry, any UUID in
a validated token was a working institution with an empty catalogue.

## Deliberately not built

- **A decision cache.** ADR 0020 makes it an obligation and it is not built yet,
  by decision: adding a cache in the same change as the extraction would have
  meant debugging two new things at once if a price came back wrong. The key is
  settled — `(productId, version)`, never invalidated by time, because publishing
  changes the key.
- **Interest accrual, tiered and balance-band pricing.** v0 is FLAT and PERCENT
  fees and per-transaction and daily limits. Anything else is a new version of
  this document.
- **Product withdrawal.** A product can be created and versioned; there is no
  retirement path, because nothing yet answers what happens to accounts held
  under a withdrawn product.
