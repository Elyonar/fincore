# Product — API

**Status:** AGREED v1.0 (2026-08-13) — amendments via [`CHANGELOG.md`](CHANGELOG.md).

Reached at `/api/product/**` through the edge. Every endpoint is tenant-scoped
from the token; no route carries a tenant id, because a staff token holds exactly
one `tenant_id` claim and cannot address another institution.

Errors follow [`error-contract.md`](../../../docs/conventions/error-contract.md):
a documented `code`, a `reason` where one code spans causes, and `details`
holding the facts a message would interpolate. The `message` field is developer
English for a log and is never shown to an end user.

## Endpoints

| Method & path | Purpose | Permission |
|---|---|---|
| `GET  /v1/products` | The catalogue, with each product's versions and their status | `products:read` |
| `GET  /v1/products/{code}` | One product by its code — the lookup Core makes before opening an account under it, so it is not a list scan per account | `products:read` |
| `POST /v1/products` | Create a product and its first version, as a draft | `products:create` |
| `POST /v1/products/{id}/versions/{version}/publish` | Make a version live. Refuses when the publisher drafted it | `products:publish` |
| `POST /v1/products/{productId}/versions` | Draft the next version, optionally copying an existing one's rules | `products:create` |
| `GET  /v1/products/{productId}/versions/{version}` | One version with its fee and limit rules | `products:read` |
| `PUT  /v1/products/{productId}/versions/{version}/fee-rules` | Replace the draft's fee schedule. Accounts are verified against the ledger | `products:create` |
| `PUT  /v1/products/{productId}/versions/{version}/limit-rules` | Replace the draft's limit schedule | `products:create` |
| `PATCH /v1/products/{productId}/versions/{version}` | Amend a draft's own fields — effective date, notes | `products:create` |
| `POST /v1/decisions/evaluate` | **The money path's question.** Permitted? At what fee? Under which version? | `products:read` |
| `GET  /v1/product-types` | The institution's own product vocabulary | `products:read` |
| `POST /v1/product-types` | Add to it | `products:create` |

## The decision endpoint

`POST /v1/decisions/evaluate` takes `productCode`, `operation`, `kycTier`,
`channel`, `amountMinor` and `currency` — and **no tenant**, which is the
structural half of tenant safety here; the gate is the other half.

It answers `200` whether or not the operation is permitted. A refusal is an
ordinary business outcome and carries a `refusal` reason; Core turns that into
its own coded `4xx` for the customer-facing call. A `4xx` here would make a
refusal indistinguishable from a malformed request, and Core would not know
whether retrying could ever help.

The response carries the `productVersion` that produced it, always — including on
a refusal. An operator asking why a transaction was refused needs to know which
version refused it, and a refusal naming no version is unanswerable once the
configuration moves on.

**Refusal reasons:** `PRODUCT_NOT_FOUND`, `OPERATION_NOT_PERMITTED`,
`LIMIT_EXCEEDED`, `CURRENCY_MISMATCH`.

## Error codes

`PRODUCT_NOT_FOUND` · `PRODUCT_CODE_TAKEN` · `INVALID_PRODUCT_TYPE` ·
`PRODUCT_VERSION_NOT_FOUND` · `VERSION_ALREADY_PUBLISHED` · `PUBLISHER_IS_AUTHOR` ·
`VERSION_NOT_DRAFT` · `RULES_INVALID` · `EFFECTIVE_FROM_IN_THE_PAST` ·
`DRAFT_CONFLICT` · `LEDGER_UNREACHABLE` · `PRICING_ACCOUNT_INVALID`

`RULES_INVALID` spans several causes and therefore carries a `reason`:
`UNKNOWN_OPERATION`, `UNKNOWN_FEE_BASIS`, `UNKNOWN_KYC_TIER`, `UNKNOWN_CHANNEL`,
`UNKNOWN_LIMIT_TYPE`, `BOUNDS_INVERTED`, `AMOUNT_MALFORMED`, `CURRENCY_INVALID`,
`RATE_OUT_OF_RANGE`, `EFFECTIVE_FROM_INVALID`, `ACCOUNT_NOT_FOUND`,
`ACCOUNT_WRONG_TYPE`.

`LEDGER_UNREACHABLE` is deliberately distinct from `PRICING_ACCOUNT_INVALID`. An
account that does not exist and a ledger that could not be asked are different
problems with different fixes, and collapsing them would let an outage look like
a configuration mistake.

## Refusals every endpoint shares

| Status | When |
|---|---|
| `401` | No token, or one this instance cannot verify. Body-less |
| `403` | Authenticated, not permitted. **Body-less** — naming the permission that would have worked hands a prober the model |
| `404` | The token names a tenant this instance does not serve. Not `403`: telling a caller which tenants exist is an enumeration oracle |
| `422` | `COMMAND_INVALID` — a malformed request that reached a handler |
