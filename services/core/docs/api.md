# Core — API Surface (v1)

**Status:** AGREED v1.6 (2026-08-06) — amendments via [`CHANGELOG.md`](CHANGELOG.md)

REST/JSON. Every request carries a validated identity token — **the tenant comes
from the token, never from a header**
([ADR 0009](../../../docs/adr/0009-service-to-service-identity.md)). Every
endpoint denies by default: the permission named below is required, and holding
a different one is a 403.

OpenAPI is generated from the code and served at `/v3/api-docs`, with Swagger UI
at `/docs`. **This table and that document are checked against each other in
both directions** by `ApiSurfaceCatalogTest`: an endpoint here that nothing
serves fails the build, and a route that appears nowhere here fails it too.

That check exists because this table was wrong for most of Core's life. Between
v1.0 and v1.4 it listed sixteen endpoints and six were built — Customer and
Product had no HTTP surface at all, and deposits, withdrawals and reversal were
tested services with no route to reach them. Nothing detected it, because the
only surface assertion was a positive spot-check for two known paths, and a
positive check cannot find an absence. Per-endpoint status markers were
considered instead and rejected: a marker reading "not yet built" is a fact
maintained by hand, which is the same category of thing that went stale here in
the first place.

## Endpoints

| Method & path | Purpose | Module | Permission | Caller |
|---|---|---|---|---|
| `POST /v1/deposits` | cash in: till → customer account | orchestration | `cash:transact` | teller, API |
| `POST /v1/withdrawals` | cash out: customer account → till | orchestration | `cash:transact` | teller, API |
| `POST /v1/transfers` | intra-tenant book transfer | orchestration | `transfers:create` | teller, API |
| `GET  /v1/transactions/{id}` | saga state — **non-mutating recovery read** | orchestration | `transfers:read` | teller, API, ops |
| `POST /v1/transactions/{id}/reverse` | **business** reversal of a completed transaction — approval required | orchestration | `transfers:reverse` | ops, supervisor |
| `POST /v1/customers` | create a customer | customer | `customers:create` | admin, API |
| `GET  /v1/customers/{id}` | customer profile, tier, status, linked accounts | customer | `customers:read` | teller, API |
| `POST /v1/customers/{id}/tier` | change KYC tier (attributed, reason required) | customer | `customers:tier` | compliance, admin |
| `POST /v1/customers/{id}/accounts` | link a ledger account to a customer | customer | `customers:link` | admin |
| `GET  /v1/customers/by-account/{ledgerAccountId}` | contact addresses and consent for the holder of an account — **no name, no tier** | customer | `customers:contact` | notification, API |
| `POST /v1/customers/{id}/consent` | record what a customer agreed to, per category and channel | customer | `customers:consent` | admin, compliance |
| `GET  /v1/products` | list products and their versions | product | `products:read` | teller, admin |
| `POST /v1/products` | create a product with a DRAFT version 1 | product | `products:create` | admin |
| `POST /v1/products/{id}/versions/{v}/publish` | publish a version (attributed; maker-checker) | product | `products:publish` | admin |
| `POST /v1/approvals` | raise a maker-checker approval, bound to a target and amount | orchestration | `approvals:make` | supervisor |
| `POST /v1/approvals/{id}/check` | approve or reject (checker ≠ maker, enforced) | orchestration | `approvals:check` | supervisor |
| `GET  /v1/ops/cases` | unresolved-outcome cases | orchestration | `ops:read` | ops |
| `POST /v1/ops/cases/{id}/resolve` | **re-attempt resolution now.** Does not accept an outcome | orchestration | `ops:resolve` | ops |

`GET /` answers with the service identity and its documentation links, and
`/actuator/health` with liveness. Neither is part of the v1 contract, and both
are open by design so a load balancer need not hold a token.

### Two administrative controls worth stating plainly

**A KYC tier change requires a reason and is recorded append-only.** A tier is
the ceiling on what a customer may move, so changing one is changing a limit.
`customer.customer_tier_changes` holds who, when, from, to and why, and a
database trigger refuses to update or delete it — an audit trail that
application code is trusted not to rewrite is one deployment away from being
wrong. A change to the tier already in force is refused rather than recorded, so
the trail is not padded with entries that look like activity.

**The author of a product version may not publish it.** Fees and limits live in
a product version, so one person drafting and publishing alone can raise a
ceiling and price against it unsupervised — a money control wearing
configuration's clothes. The publisher is taken from the token, never the body,
and `product_versions.publisher_differs_from_author` refuses the write
regardless. This is the same rule as `checker_differs_from_maker` on
`orchestration.approvals`, applied to a different subject; Product cannot use
that table, both because it may not depend on Orchestration and because an
approval there is bound to a saga id and an amount, neither of which a product
version has.

**There is no endpoint for a compensating reversal, deliberately.** A saga
undoing its own posting after a downstream `DEFINITE_FAILURE` is automated and
internal — it has no caller, no approval, and no API surface, because exposing it
would create exactly the discretionary money-movement path that keeping the two
kinds of reversal separate exists to prevent. See
[`saga-protocol.md`](saga-protocol.md).

**`POST /v1/ops/cases/{id}/resolve` deliberately takes no outcome.** It asks
Core to retry the derived key and see what the Ledger says; the case closes only
when the Ledger answers definitively. There is no endpoint by which a human
declares that a transaction posted — the moment an operator can assert an
outcome, the outcome protocol's central guarantee becomes advisory. There is no
exception: even after a Ledger restore, resolution is mechanical replay rather
than human judgement ([`saga-protocol.md`](saga-protocol.md)).

Not in v1, deliberately: inter-bank transfers, bulk disbursement, standing
orders, holds, interest accrual, lending. The connector seam is designed
([`saga-protocol.md`](saga-protocol.md)) and not built.

## The contract property that matters most

**An unknown outcome is reported as a 5xx, never as a success-shaped 202.**

When Core's ledger call resolves to `UNKNOWN`, Core genuinely does not know
whether the money moved. It therefore returns a 5xx, which under Core's own
retry rule obliges the caller to **retry the same idempotency key** — and that
retry is what eventually resolves the saga.

A `202 Accepted` was considered and rejected. It is success-shaped: it invites a
channel to record "submitted" and move on, when what the protocol needs is for
the caller to keep asking. The failure mode of a wrong 202 is a teller telling a
customer the transfer went through while the platform does not know whether it
did.

On retry, Core finds the saga already in `POSTING` and either returns the
now-known result or the same 5xx. `GET /v1/transactions/{id}` exists so a
crashed caller can ask what state its transaction is in **without mutating
anything** — the same courtesy the ledger extends for holds.

## Contract properties

- **Idempotency on every creating request.** Caller-supplied key, ≤ 200
  characters, unique per tenant. Replays return the original result.
- **Key reuse is loud.** Same key with a different payload fingerprint →
  `409 IDEMPOTENCY_KEY_REUSED`. Never a silent wrong answer.
- **The retry rule, both halves, binding on every caller.** Any **4xx** is
  terminal for that key — a new logical attempt mints a new key. Any **timeout,
  connection failure or 5xx** means the outcome is unknown and the caller
  **must** retry the same key until it receives a definitive answer. Minting a
  new key for an unknown outcome is how double payments are created, and no
  downstream control can catch it.
- **The fingerprint covers economic content only** — amounts, accounts,
  currency, operation, customer. Free-text description and the initiating user
  are excluded, so a legitimate retry from a different pod or session replays
  rather than 409s. Two requests that move the same money identically are the
  same request.
- **Rejections are total.** No saga, no reservation, no ledger call, no event.
- **Money is integer minor units**, serialized as decimal strings in responses
  and event payloads; requests accept numbers or strings.
- **Fees are disclosed before they are charged.** A response carries the fee
  that was applied and the product version that decided it.
- **Tenant scoping is implicit.** Not-found and wrong-tenant are deliberately
  indistinguishable.

## Error catalog

Distinct, documented, and tested — one generic error is a debugging cost paid
forever by every caller.

| Code | Meaning | Retry same key? |
|---|---|---|
| `CUSTOMER_NOT_FOUND` | unknown customer, or another tenant's | no |
| `CUSTOMER_NOT_ACTIVE` | customer is dormant or closed | no |
| `ACCOUNT_NOT_LINKED` | the account is not linked to this customer | no |
| `PRODUCT_NOT_FOUND` | no product, or no published version in effect | no |
| `OPERATION_NOT_PERMITTED` | the product forbids this operation for this tier or channel | no |
| `LIMIT_EXCEEDED` | per-transaction or daily limit would be breached | no — new key after the window rolls |
| `CURRENCY_MISMATCH` | entry currency ≠ account currency | no |
| `INSUFFICIENT_FUNDS` | relayed from the Ledger; the account would go available < 0 | no — new key after funding |
| `IDEMPOTENCY_KEY_REUSED` | same key, different payload fingerprint | no — caller bug |
| `AMOUNT_INVALID` | zero, negative, or above the platform cap | no |
| `WASH_TRANSACTION` | source and destination are the same account | no |
| `TRANSACTION_NOT_FOUND` | unknown saga, or another tenant's | no |
| `NOT_REVERSIBLE` | target is not `COMPLETED`, or is itself a reversal | no |
| `APPROVAL_REQUIRED` | reversal without a valid maker-checker approval reference | no — obtain approval |
| `APPROVAL_INVALID` | the approval is unapproved, already spent, or bound to a different target or amount → 403 | no — obtain approval |
| `ALREADY_REVERSED` | a reversal exists; the response carries its id | converge on the returned id |
| `EXTERNAL_REF_TAKEN` | the tenant already numbered a customer with this reference → 409 | no — caller bug |
| `ACCOUNT_ALREADY_HELD` | that ledger account is already live-linked to a customer → 409 | no |
| `REASON_REQUIRED` | a tier change carried no reason → 422 | no |
| `TIER_UNCHANGED` | the customer already holds that tier → 422 | no |
| `CONSENT_INCOMPLETE` | a consent record omitted its category, channel or answer → 422 | no |
| `PRODUCT_CODE_TAKEN` | the tenant already has a product with this code → 409 | no — caller bug |
| `INVALID_PRODUCT_TYPE` | not one of the supported product types → 422 | no |
| `PRODUCT_VERSION_NOT_FOUND` | no such version, or another tenant's → 404 | no |
| `VERSION_ALREADY_PUBLISHED` | that version is live already → 409 | no |
| `PUBLISHER_IS_AUTHOR` | the principal wrote this version and may not publish it → 403 | no — a colleague must publish |

`NOT_REVERSIBLE` is checked **before** the approval is examined. A transaction
that has already been reversed is refused no matter what authority accompanies
the request, so collecting signatures is never a route around it.

**`PENDING_RESOLUTION` is a state, not an error code.** It appears in
`GET /v1/transactions/{id}` and never as a rejection — a transaction whose
outcome is being determined has not failed, and calling it a failure would
invite exactly the compensation the outcome protocol forbids.

## Example — a transfer

```json
POST /v1/transfers
{
  "idempotencyKey": "teller-01-2026-08-05-000417",
  "fromAccountId": "…customer-a…",
  "toAccountId":   "…customer-b…",
  "amountMinor":   "500000",
  "currency":      "NGN",
  "productCode":   "AJO_DAILY",
  "description":   "transfer to Ada"
}
```

```json
201 Created
{
  "transactionId": "…saga…",
  "state":         "COMPLETED",
  "amountMinor":   "500000",
  "feeMinor":      "2000",
  "currency":      "NGN",
  "productVersion": 3,
  "ledgerTransactionId": "…",
  "initiatedBy":   "user:ada.o@branch-01"
}
```

The principal, the fee, and the counterparty settle in **one** ledger
transaction — debit the sender 502,000, credit the recipient 500,000, credit fee
income 2,000. Replaying the same body returns the same response; replaying the
key with a different body returns `409 IDEMPOTENCY_KEY_REUSED`.
