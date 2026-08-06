# Core — API Surface (v1)

**Status:** AGREED v1.0 (2026-08-06) — amendments via [`CHANGELOG.md`](CHANGELOG.md)

REST/JSON. OpenAPI is generated from the code once implementation lands; this
document is the agreed contract shape. Every request carries a validated
identity token — **the tenant comes from the token, never from a header**
([ADR 0009](../../../docs/adr/0009-service-to-service-identity.md)).

## Endpoints

| Method & path | Purpose | Module | Caller |
|---|---|---|---|
| `POST /v1/deposits` | cash in: till → customer account | orchestration | teller, API |
| `POST /v1/withdrawals` | cash out: customer account → till | orchestration | teller, API |
| `POST /v1/transfers` | intra-tenant book transfer | orchestration | teller, API |
| `GET  /v1/transactions/{id}` | saga state — **non-mutating recovery read** | orchestration | teller, API, ops |
| `POST /v1/transactions/{id}/reverse` | **business** reversal of a completed transaction — approval required | orchestration | ops, supervisor |
| `POST /v1/customers` | create a customer | customer | admin, API |
| `GET  /v1/customers/{id}` | customer profile, tier, status, linked accounts | customer | teller, API |
| `POST /v1/customers/{id}/tier` | change KYC tier (attributed) | customer | compliance, admin |
| `POST /v1/customers/{id}/accounts` | link a ledger account to a customer | customer | admin |
| `GET  /v1/products` | list products and live versions | product | teller, admin |
| `POST /v1/products` | create a product (DRAFT) | product | admin |
| `POST /v1/products/{id}/versions/{v}/publish` | publish a version (attributed; maker-checker) | product | admin |
| `POST /v1/approvals` | raise a maker-checker approval, bound to a target and amount | orchestration | supervisor |
| `POST /v1/approvals/{id}/check` | approve or reject (checker ≠ maker, enforced) | orchestration | supervisor |
| `GET  /v1/ops/cases` | unresolved-outcome cases | orchestration | ops |
| `POST /v1/ops/cases/{id}/resolve` | **re-attempt resolution now.** Does not accept an outcome | orchestration | ops |

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
| `ALREADY_REVERSED` | a reversal exists; the response carries its id | converge on the returned id |

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
