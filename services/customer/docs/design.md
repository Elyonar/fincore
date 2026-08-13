# Customer — Design

**Status:** AGREED v1.0 (2026-08-13) — implemented from here; amendments via
[`CHANGELOG.md`](CHANGELOG.md).

**Inherited, not invented.** This service was designed and built as a module
inside Core, and the argument for its domain rules is
[`services/core/docs/design.md`](../../core/docs/design.md) §Customer — still the
record of *why*. What changed is packaging, and that decision is
[ADR 0020](../../../docs/adr/0020-customer-and-product-become-deployables.md).

This document exists because the packaging change made Core's design doc the
wrong home for amendments: a change to this service's contract recorded in
another service's changelog is a change nobody looking here will find. v1.0 is
the design as extracted — no new decisions, one new owner.

---

## What this service is for

The people an institution banks: who they are, what KYC tier they hold, how to
reach them, whether they agreed to be reached, what number they quote, and which
accounts they hold.

**It owns identity and never money.** Balances, entries and transaction history
live in the ledger. A customer profile that also knew a balance would be a second
place to be wrong about one, and the two would drift from the first reconciliation
onward. `BoundaryTest` fails the build if a ledger client or a money type appears
here.

## The property that shapes everything else

**This is the only schema on the platform holding personal data.** That is not a
footnote — it is why the boundaries are drawn where they are:

- no ledger client, so a defect here cannot move money;
- no money type, so it cannot be asked to compute one;
- the published `api` surface is deliberately narrow, which is what keeps the
  money path free of a dependency on PII;
- the blast radius of a compromise here is a disclosure, not a transfer.

The narrowness of the published surface is load-bearing rather than tidy. It is
also what made this extraction cheap: two methods became a client.

## The decisions that shape it

**Not-found and wrong-tenant are one answer.** A customer that exists for another
institution and a customer that does not exist both answer `404`. Distinguishing
them confirms that a person banks somewhere, which is the disclosure this service
exists to prevent.

**A KYC tier change requires a reason and is recorded append-only.**
`customer_tier_changes` is protected by `reject_tier_history_edit`: no update, no
delete. A tier that moved without a trace is a tier nobody can defend to a
regulator, and the reason is the part an auditor actually reads.

**Consent is per category and per channel, and its history is append-only too.**
`consent_changes` carries the same trigger. Absent consent is *granted* for
everything except `MARKETING` — a transactional alert about a customer's own money
is not marketing, and requiring an opt-in for it would suppress the messages
customers most expect.

**The institution numbers its own customers.** `external_ref` is the tenant's own
customer number, unique within the tenant, and blank means the institution's
numbering series answers. Before that, `external_ref` was `NOT NULL` with no
default and taken verbatim from the request, so every branch invented a scheme
and the first collision surfaced as a `409` at the counter.

**Account ownership lives here, and Core asks across the boundary for it.**
`customer_accounts` carries both a domain fact — this person holds this account —
and a transactional one — this account is governed by this product. ADR 0020
accepted that cost explicitly: `productOfHeldAccount` is a **security control**,
not a convenience. The money path resolves the governing product *from the
account* precisely so a caller cannot name the rules that judge its own
transaction.

## What it talks to, and what it may not

| Direction | Who | Why |
|---|---|---|
| Inbound | Core (money path) | Eligibility, and which product governs a held account |
| Inbound | Notification | Who holds an account, and what they consented to ([ADR 0019](../../../docs/adr/0019-tenant-scoped-service-principals.md)) |
| Inbound | Staff via the edge | Registration, tiers, contact, consent, numbering |
| Outbound | **Nothing** | This service answers questions; it asks none |

Neither of its two hot-path answers may be cached. A customer frozen ten seconds
ago must be refused now, and `productOfHeldAccount` is a control rather than a
lookup. ADR 0020 states this as an accepted per-transaction cost.

**It never calls Core.** Enforced by the POM and by `BoundaryTest`
(ADR 0020, obligation 3).

## Tenancy

One deployed instance serves one institution
([ADR 0021](../../../docs/adr/0021-one-instance-serves-one-institution.md)), and
this service still carries the full three-layer pattern of
[ADR 0007](../../../docs/adr/0007-tenant-isolation-pattern.md) — the more
willingly here than anywhere, because this is the schema where a cross-tenant leak
would be a disclosure of personal data rather than a wrong number.

A tenant registry (`customer.tenants`) gates every request; an unknown tenant
answers `404`, not `403`.

## Deliberately not built

- **A contact-update endpoint.** A customer's phone and email are set at
  registration and there is no route to change them. This is a real gap, not a
  decision: it means a correction requires SQL, and it blocks the notification
  path from being exercised with real data.
- **Field-level PII encryption.** The addresses this service stores are
  plaintext. Notification encrypts its own copy of an address; this one does not,
  and the asymmetry is not defensible long-term.
- **Deactivation and offboarding.** A customer can be registered and re-tiered.
  There is no path to close, merge or erase one — and erasure in particular needs
  an answer about what happens to the ledger entries that reference them.
- **Mandates.** Named in the inherited design, not implemented.
