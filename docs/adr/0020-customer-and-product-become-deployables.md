# ADR 0020 — Customer and Product become deployables of their own

**Status:** Proposed · 2026-08-12
**Supersedes:** [0006](0006-core-is-one-deployable.md), whose extraction triggers this fires.

## Context

ADR 0006 shipped Customer, Product and Orchestration as three modules inside one
Core deployable, and was right to. It also did something better than deciding:
it wrote down, in advance, the conditions under which the decision should be
reversed, and insisted that "a module is extracted when one of these is
**demonstrated**, never because a diagram would look cleaner."

Two of its six triggers have arrived:

- **Trigger 1 — Customer.** Customer is the only module holding PII, and it is
  now the module other services will consume. Lending, a partner-facing API and
  any customer-owned surface all need it, and none of them need the money path.
  While it lives inside Core, every one of those consumers reaches customer data
  by calling the service that moves money.
- **Trigger 4 — Product.** Pricing is where country and segment divergence
  lands. It already releases on a different rhythm from the money path: a
  repricing is a new version, not a deployment.

0006 expected Customer first and Product second. That order is right for
*value* and wrong for *sequencing*, and this ADR takes them in the other order
for reasons given below.

### What had to be true before either could move

0006's decisive technical argument was not modularity. It was this:

> Limit enforcement must be a *reservation*, not a check: two concurrent
> transfers that each observe the limit unbreached will both proceed. A
> reservation is only safe if it is taken in the same transaction that creates
> the saga.

That argument is still correct, and it is the reason most attempts at this
extraction would be wrong. It does not block this one, and the reason is
mechanical rather than hopeful:

- `limit_reservations` lives in the **orchestration** schema, not Product's.
- `SagaRecords.open` is `@Transactional`, and inserts the saga row and the
  reservation row inside it.
- `ProductDecision` is a **parameter** to that method. It is computed before the
  transaction opens.

So the reservation and the saga are already one local transaction, and Product
supplies only the ceiling the reservation is taken against. Extraction moves the
*rule*, never the reservation. 0006's argument survives it untouched, which is
precisely why it can be reversed now and could not have been earlier.

Three further facts were checked rather than assumed:

- **No cross-schema foreign keys exist** anywhere between `customer`, `product`,
  `organization`, `orchestration` and `platform`. Each module already owns its
  data outright, so this is a schema move rather than a data untangling.
- **The decision reads hold no transaction.** `TransferService.execute` carries
  no `@Transactional`; the boundaries live inside `SagaRecords`. The calls to
  Customer and Product already happen outside any transaction, in the same shape
  as the ledger call in Phase B.
- **A published product version is immutable by database trigger.**
  `reject_published_edit` and `reject_published_rule_write` refuse any edit to a
  published version or its rules. A decision is therefore a pure function of
  immutable configuration.

## Decision

**Customer and Product each become an independent deployable, with its own
database, its own role, its own tenant registry and its own service identity —
the same shape as Ledger, Identity and Notification. Core keeps Orchestration,
Organization and Admin, and becomes a client of both.**

**Product is extracted first.** Not because it matters more — Customer does —
but because its decision is a pure function of trigger-enforced immutable data,
which makes it the one extraction whose hot-path cost can be driven to zero by a
version-keyed cache, and therefore the safest place to learn the pattern: edge
route, service principal, tenant-registry row, cache invalidation on publish.
Customer follows once that pattern is proven.

**The hot-path contract is frozen at three questions**, exactly the three Core
asks today:

| Question | Service | Cacheable |
|---|---|---|
| `evaluate(product, tier, channel, amount, currency)` | Product | Yes — keyed by version, which cannot change |
| `check(customerId)` → status, KYC tier | Customer | **No** |
| `productOfHeldAccount(customerId, ledgerAccountId)` | Customer | **No** |

The last two are deliberately not cacheable. A customer frozen ten seconds ago
must be refused now, and `productOfHeldAccount` is a **security control** rather
than a convenience: the money path resolves the governing product from the
account precisely so that a caller cannot name the rules that judge its own
transaction. Caching either would trade a correctness property for latency.

**Core pays one uncacheable round trip per transaction, and that is accepted.**
It already makes one — the ledger, in Phase B, holding no transaction. A second
intra-cluster call on that path is a known cost, not a discovered one.

**The `LedgerAccounts` inversion is deleted.** Product declared that port and
Orchestration implemented it, purely because a Core module was forbidden an HTTP
client (hard rule 3). A deployable is allowed one, so Product verifies fee
accounts against the ledger itself and the inversion goes away.

## Consequences

### Accepted costs, stated rather than elided

- **Two more deployables to operate**, each with a database, a role, a
  migration history, a tenant-registry row and an mTLS identity. This is the
  cost 0006 correctly refused to pay when the triggers had not fired.
- **One extra network hop per transaction**, uncacheable, from the Customer
  calls. Measured in the same intra-cluster terms as the existing ledger call.
- **A partial failure mode that did not exist.** Customer or Product being
  unreachable now refuses transactions that would previously have proceeded.
  This must fail closed: an unreachable pricing service is not "no limit".
- **Account ownership spans a boundary.** `customer_accounts` carries both a
  domain fact (this person holds this account) and a transactional one (this
  account is governed by this product). It moves with Customer, and Core asks
  across the boundary for a control it enforces itself.

### Obligations this creates

1. Both services fail **closed**. A timeout or an unreachable dependency refuses
   the transaction with a coded error; it never falls back to a default rule, a
   cached-past-expiry decision, or an unpriced transaction.
2. Product's decision cache is keyed by `(productId, version)` and never
   invalidated by time, because the data cannot change. Publishing a new version
   changes the key.
3. Neither service gains a client onto the money path. Core calls them; they do
   not call Core. Enforced by their POMs and by an ArchUnit rule in each.
4. `seed-registries.sh` — and eventually ADR 0016's `TenantSeeder` — registers
   five registries, not three. A tenant missing from one authenticates and then
   404s, which is the defect that design exists to remove.

### What this does not change

The ledger stays the only book. Organization stays inside Core: it is off the
money path, but at three tables it would be a deployable bought for nothing.
Core remains the money path — sagas, tills, approvals, ops cases,
reconciliation — which is a coherent service rather than what is left over.

## Revisiting

If the measured per-transaction cost of the Customer round trip exceeds the
budget stated for the transfer path, the answer is **not** to cache customer
status. It is to move the account-holding projection Core needs for its own
security control back into Core, leaving Customer to own the person. That
split is the next decision, and it should be taken with a measurement rather
than in advance.
