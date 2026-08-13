# Customer — API

**Status:** AGREED v1.0 (2026-08-13) — amendments via [`CHANGELOG.md`](CHANGELOG.md).

Reached at `/api/customer/**` through the edge. Every endpoint is tenant-scoped
from the token; no route carries a tenant id.

Errors follow [`error-contract.md`](../../../docs/conventions/error-contract.md):
a documented `code`, a `reason` where one code spans causes, and `details`. The
`message` field is developer English for a log and is never shown to an end user.

## Endpoints

| Method & path | Purpose | Permission | Caller |
|---|---|---|---|
| `POST /v1/customers` | Register a customer. Blank `externalRef` draws from the institution's numbering series | `customers:create` | admin, teller |
| `GET  /v1/customers` | The register, searchable | `customers:read` | admin, teller |
| `GET  /v1/customers/{id}` | One profile, with live account links | `customers:read` | admin, teller |
| `POST /v1/customers/{id}/tier` | Change a KYC tier. **A reason is required**, and the change is recorded append-only | `customers:tier` | compliance |
| `POST /v1/customers/{id}/accounts` | Link a ledger account to this customer, with the product governing it | `customers:link` | admin, Core |
| `GET  /v1/customers/by-account/{ledgerAccountId}` | **Who holds this account, and what they consented to.** The notification service's question | `customers:contact` | Notification |
| `POST /v1/customers/{id}/consent` | Record a consent decision, per category and channel | `customers:consent` | admin, teller |
| `GET  /v1/eligibility/{customerId}` | **The money path's question.** Status and KYC tier | `customers:read` | Core |
| `GET  /v1/eligibility/{customerId}/account-product` | Which product governs a held account — a security control, not a lookup | `customers:read` | Core |
| `GET  /v1/kyc-tiers` | The institution's own tier vocabulary | `customers:read` | admin |
| `POST /v1/kyc-tiers` | Add to it | `customers:tier` | compliance |
| `GET  /v1/numbering/{series}` | How customers and their accounts are numbered, and the next of each | `customers:read` | admin |
| `POST /v1/numbering/{series}` | Change a series — prefix, width, next value | `customers:create` | admin |
| `POST /v1/customers/{customerId}/accounts/link` | Administrative link of an already-open account | `customers:link` | admin |

## The two hot-path reads

`GET /v1/eligibility/{customerId}` and
`GET /v1/eligibility/{customerId}/account-product` are called by Core on every
transaction, and **neither may be cached**. A customer frozen ten seconds ago
must be refused now; and `account-product` is how the money path resolves the
governing product *from the account*, so that a caller cannot name the rules that
judge its own transaction. Caching either trades a correctness property for
latency.

`GET /v1/customers/by-account/{id}` is Notification's, behind
`customers:contact` — a permission that exists so a service can read an address
and a consent decision without holding `customers:read` over the whole register.

## Error codes

`CUSTOMER_NOT_FOUND` · `EXTERNAL_REF_TAKEN` · `ACCOUNT_ALREADY_HELD` ·
`ACCOUNT_NUMBER_TAKEN` · `REASON_REQUIRED` · `NAME_REQUIRED` ·
`PRODUCT_REQUIRED` · `TIER_UNCHANGED` · `CONSENT_INCOMPLETE` ·
`ACCOUNT_NOT_OPENED`

`TIER_UNCHANGED` is a refusal rather than a no-op on purpose: a tier change
carries a reason and an audit row, and silently accepting one that changes
nothing writes a justification for an event that did not happen.

## Refusals every endpoint shares

| Status | When |
|---|---|
| `401` | No token, or one this instance cannot verify. Body-less |
| `403` | Authenticated, not permitted. **Body-less** |
| `404` | No such customer, **or** the customer belongs to another tenant, **or** the token names a tenant this instance does not serve. Deliberately indistinguishable — telling them apart confirms that a person banks somewhere |
| `422` | `COMMAND_INVALID` — a malformed request that reached a handler |
