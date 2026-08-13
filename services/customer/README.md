# Customer Service

> **The people this institution banks.** Who they are, what tier they are on,
> how to reach them and whether they agreed to be reached, what number they
> quote, and which accounts they hold. It holds the platform's only PII and
> moves no money.

**Status: design AGREED v1.0; implemented and running. 35 tests green against
real PostgreSQL.**

**The design it inherited is now recorded here.** This service was designed and
built as a module inside Core, and the *argument* for its domain rules is still
[`services/core/docs/design.md`](../core/docs/design.md) §Customer — cited rather
than copied. What this service now owns is its own agreed contract
([`docs/design.md`](docs/design.md)) and its own changelog, because an amendment
to this service's contract recorded under another service's version number is one
nobody looking here would find. That matters more here than anywhere: this is the
only schema holding personal data, and the documents describing what happens to
it should be findable from the service that holds it.

---

## Memory map

| You want to know… | Read |
|---|---|
| Why this is a deployable rather than a Core module | [ADR 0020](../../docs/adr/0020-customer-and-product-become-deployables.md) |
| The agreed design, and what it deliberately does not build | [`docs/design.md`](docs/design.md) |
| Every endpoint, permission and error code | [`docs/api.md`](docs/api.md) |
| Tables, triggers, RLS and migrations | [`docs/data-model.md`](docs/data-model.md) |
| What is tested, and what is not | [`docs/testing.md`](docs/testing.md) |
| What changed since the design was agreed | [`docs/CHANGELOG.md`](docs/CHANGELOG.md) |
| The original argument for the domain rules | [`services/core/docs/design.md`](../core/docs/design.md) §Customer |
| How tenant isolation works here | [ADR 0007](../../docs/adr/0007-tenant-isolation-pattern.md) |
| How another service gets permission to ask it anything | [ADR 0019](../../docs/adr/0019-tenant-scoped-service-principals.md) |
| Platform hard rules | [`AGENTS.md`](../../AGENTS.md) |

## Quick facts

| | |
|---|---|
| Language / framework | Java 25 LTS, Spring Boot |
| Storage | PostgreSQL, own database, one `customer` schema |
| Tables | `customers`, `customer_accounts`, `communication_consent`, `consent_changes`, `customer_tier_changes`, `kyc_tiers`, `numbering`, `tenants` |
| Database role | `customer_app` for traffic; migrations run as the owner |
| Money representation | none — this service moves no money |
| Calls out to | **nothing.** It is called; it calls nobody |
| Events | publishes none |
| Port | 8085 in the compose network, unpublished on the host — reached through the edge at `/api/customer/` |

## What it owns

**Identity and status.** A customer, their name, their status, and the
`external_ref` the institution quotes back to them — allocated from its own
`numbering` series, or supplied when an institution arrives with numbers already
on passbooks.

**KYC tier, and the ladder itself.** A tier decides what somebody may move and
through which channel. **The tiers an institution recognises are its own
vocabulary, not the platform's** — `TIER_1..3` is Nigeria's answer and wrong for
Kenya, Ghana and the CFA zone — so the list is a tenant table with a route, not
a `CHECK` constraint. Every change of tier is recorded in `customer_tier_changes`
with a reason, because the audit answers *why*, not just *what*.

**Contact and consent, kept apart on purpose.** Having somebody's number is not
permission to use it. `communication_consent` is a separate table with its own
history in `consent_changes`, and the eligibility read that Notification depends
on returns both together so a sender can never learn an address without learning
whether it may be used.

**Which accounts a customer holds.** `customer_accounts` links a person to a
ledger account and the product it is held under. The link is this service's; the
account is the ledger's; the product is Product's. Nothing here duplicates
either.

## What it deliberately does not own

- **Balances and money.** A customer's balance is the ledger's answer, joined on
  by Core for the 360 view. Storing one here would be a second answer to a
  question that must only have one.
- **Opening an account.** That is a composition — a ledger account, a product
  check, then a customer record — and it lives in Core, which is the only place
  allowed to see all three (ADR 0020).
- **Pricing.** What a transaction costs is Product's, decided against the tier
  this service holds.

## The three things most likely to be got wrong

**1. PII lives here and nowhere else.** The ledger stores no names; it knows a
customer only by an opaque `customer_ref`. Do not add a name, phone or email to
any other service's table to save a call — the reason the ledger holds none is
that a breach of the ledger must not be a breach of the customer book.

**2. Tenant isolation is a database policy, not application code.** Every table
carries `tenant_id`, RLS is `FORCE`d, and `customer_app` is neither `SUPERUSER`
nor `BYPASSRLS` — PostgreSQL skips RLS entirely for either, which would leave
every policy inert while the catalog still reported it enabled. A query that
forgets to scope does not leak; it returns nothing.

**3. Retiring a tier deactivates it; it never deletes it.** People already on a
tier keep it until they are reviewed, and a limit rule written against it must
keep pricing them. Deleting the row would leave both pointing at nothing.

## Run

```bash
# whole stack
docker compose up --build

# this service's suite, against its own database
./mvnw -pl services/customer verify
```

The suite runs against real PostgreSQL and never an in-memory substitute: the
trigger, constraint and row-level-security behaviour under test is PostgreSQL's,
and a substitute that did not enforce it would make the suite agree with a
guarantee nothing was actually keeping.

## Known limitations

- **No contact-update endpoint.** A customer's phone and email are set at
  registration and there is no route to change them, so a correction requires
  SQL — and the notification path cannot be exercised with real data without it.
- **Contact details are stored in plaintext.** The notification service encrypts
  its own copy of the same address; this one does not.
- **No erasure or offboarding.** A customer can be registered and re-tiered,
  never closed, merged or deleted. The design question is open rather than the
  work being unbuilt: what erasure means against an immutable ledger is
  unanswered.
- **No error-catalog test and no schema-presence suite.** The scaffold asks for
  both. The ten codes in [`docs/api.md`](docs/api.md) are checked by reading, and
  the append-only triggers are reached indirectly by the API suites.
