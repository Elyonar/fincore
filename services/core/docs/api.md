# Core — API Surface (v1)

**Status:** AGREED v1.3 (2026-08-06) — amendments via [`CHANGELOG.md`](CHANGELOG.md)

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

Every rejection returns the shape defined in
[`docs/conventions/error-contract.md`](../../../docs/conventions/error-contract.md):

```json
{
  "code": "AMOUNT_INVALID",
  "reason": "AMOUNT_NOT_POSITIVE",
  "message": "amountMinor must be positive",
  "retryableWithSameKey": false,
  "details": { "field": "amountMinor", "supplied": "-500" }
}
```

`code` is what a caller branches on. `reason` separates causes that share a
code. `details` carries the facts a translated message interpolates. **`message`
is developer English for a log** — never displayed to an end user, never parsed,
and free to be reworded without an amendment. A channel serving a francophone
tenant renders its own string from `code`, `reason` and `details`.

| Code | Meaning | Retry same key? |
|---|---|---|
| `CUSTOMER_NOT_FOUND` | unknown customer, or another tenant's | no |
| `CUSTOMER_NOT_ACTIVE` | customer is dormant or closed | no |
| `ACCOUNT_NOT_LINKED` | the account is not linked to this customer | no |
| `PRODUCT_NOT_FOUND` | no product, or no published version in effect | no |
| `OPERATION_NOT_PERMITTED` | the product forbids this operation for this tier or channel | no |
| `LIMIT_EXCEEDED` | per-transaction or daily limit would be breached | no — new key after the window rolls |
| `AMOUNT_INVALID` | zero, negative, or above the platform cap | no |
| `COMMAND_INVALID` | a required field is absent or malformed — see reasons | no |
| `CURRENCY_MISMATCH` | entry currency ≠ account currency | no |
| `WASH_TRANSACTION` | source and destination are the same account | no |
| `TILL_NOT_OPEN` | the teller's till is not open | no |
| `FEE_EXCEEDS_DEPOSIT` | the fee would consume more than the deposit | no |
| `INSUFFICIENT_FUNDS` | relayed from the Ledger; the account would go available < 0 | no — new key after funding |
| `IDEMPOTENCY_KEY_REUSED` | same key, different payload fingerprint | no — caller bug |
| `TRANSACTION_NOT_FOUND` | unknown saga, or another tenant's | no |
| `NOT_REVERSIBLE` | target is not `COMPLETED`, or is itself a reversal | no |
| `APPROVAL_REQUIRED` | reversal without a valid maker-checker approval reference | no — obtain approval |
| `ALREADY_REVERSED` | a reversal exists; the response carries its id | converge on the returned id |
| `LEDGER_REFUSED` | the Ledger refused for a reason Core does not model; its code is in `details.ledgerCode` | no |
| `LEDGER_UNREACHABLE` | the connection was refused — nothing was sent, so this is definite | yes — after backoff |
| `OUTCOME_UNKNOWN` | the outcome is not known; 503, and the saga id is returned to poll | **yes — same key** |

### Reasons

| Code | Reason | Cause | `details` |
|---|---|---|---|
| `COMMAND_INVALID` | `FIELD_REQUIRED` | a required field was absent | `field` |
| `COMMAND_INVALID` | `IDEMPOTENCY_KEY_REQUIRED` | a posting without a key is not retryable | `field` |
| `COMMAND_INVALID` | `TOO_FEW_ENTRIES` | fewer than two entries | `limit`, `supplied` |
| `COMMAND_INVALID` | `STEP_CONTAINS_SEPARATOR` | a saga step name contains `:` | `field` |
| `COMMAND_INVALID` | `DERIVED_KEY_TOO_LONG` | the derived key exceeds the Ledger's cap | `maxLength` |
| `AMOUNT_INVALID` | `AMOUNT_NOT_POSITIVE` | zero or negative amount | `field`, `supplied` |
| `AMOUNT_INVALID` | `AMOUNT_SIGN_ON_ENTRY` | an entry carried a sign; direction carries it | `supplied` |
| `LIMIT_EXCEEDED` | `PER_TRANSACTION_LIMIT` | the amount alone breaches the per-transaction limit | `limit`, `supplied` |
| `LIMIT_EXCEEDED` | `DAILY_LIMIT` | the amount breaches the rolling daily limit | `limit`, `supplied` |
| `NOT_REVERSIBLE` | `NOT_COMPLETED` | the target saga is not `COMPLETED` | — |
| `NOT_REVERSIBLE` | `IS_A_REVERSAL` | the target is itself a reversal | — |
| `OUTCOME_UNKNOWN` | `READ_TIMEOUT` | the Ledger did not answer in time | `transactionId` |
| `OUTCOME_UNKNOWN` | `NO_TRANSACTION_ID` | a 2xx arrived without a transaction id | `transactionId` |
| `OUTCOME_UNKNOWN` | `UNEXPECTED_STATUS` | an unmodelled HTTP status | `transactionId` |

**The Ledger's codes are mapped, never forwarded.** A Ledger error Core does not
model becomes `LEDGER_REFUSED` with the original in `details.ledgerCode`, rather
than appearing verbatim as if it were a Core code. Otherwise a caller would meet
codes that Core's own catalog does not list and cannot translate, and the two
catalogs would silently merge without anyone deciding they should.

**Denials name no permissions.** A 403 is body-less; naming the permission that
would have worked is a map handed to a prober.

`ErrorCodeCatalogTest` fails the build if a code or reason exists in the source
and not in these tables, or the reverse.

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
