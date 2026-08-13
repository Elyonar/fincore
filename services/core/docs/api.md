# Core — API Surface (v1)

**Status:** AGREED v2.4 (2026-08-11) — amendments via [`CHANGELOG.md`](CHANGELOG.md)

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

> **Customer and Product left this service (ADR 0020).** Their endpoints are served by the
> `customer` and `product` deployables and are documented with them — `/api/customer/**` and
> `/api/product/**` at the edge. What stays here is what Core genuinely composes: opening an
> account (a ledger account, a product check and a customer record, in that order), and the 360
> view that joins held accounts with the ledger's balances. A documented endpoint that nothing
> serves is worse than an undocumented one, because an integrator plans against it — which is why
> `ApiSurfaceCatalogTest` refuses to let this table drift from what is actually built.
>
> Two vocabularies moved with them and are worth naming here because Core enforces something about
> each. `/v1/kyc-tiers` belongs to the customer service and `/v1/product-types` to the product
> service — but Core checks a limit rule's tier against the first before writing it through, for the
> same reason it checks a fee rule's account against its own register: a rule naming a tier nobody
> can hold stores cleanly and then never matches, which reads as a configured ceiling and behaves as
> a blanket refusal.


| Method & path | Purpose | Module | Permission | Caller |
|---|---|---|---|---|
| `POST /v1/deposits` | cash in: till → customer account; priced by the account's own product | orchestration | `cash:transact` | teller, API |
| `POST /v1/withdrawals` | cash out: customer account → till; priced by the account's own product | orchestration | `cash:transact` | teller, API |
| `POST /v1/transfers` | intra-tenant book transfer | orchestration | `transfers:create` | teller, API |
| `GET  /v1/transactions/{id}` | saga state and the accounts it moved between — **non-mutating recovery read** | orchestration | `transfers:read` | teller, API, ops, consumers |
| `POST /v1/transactions/{id}/reverse` | **business** reversal of a completed transaction — approval required | orchestration | `transfers:reverse` | ops, supervisor |
| `GET  /v1/customers/{id}/accounts` | held accounts with the ledger's balances joined on | orchestration | `customers:read` | teller, API |
| `GET  /v1/accounts/{ledgerAccountId}/statement` | the ledger's period statement, passed through byte-for-byte (`from`, `to`) | orchestration | `transfers:read` | teller, API |
| `GET  /v1/tills/{id}/activity` | the till's day: its sagas and net position (`date`) | orchestration | `tills:read` | supervisor |
| `GET  /v1/approvals/pending` | the checker's queue, oldest first | orchestration | `approvals:check` | supervisor |
| `POST /v1/customers/{customerId}/accounts/open` | open a ledger account for a customer, number it, and record the product it is held under — a code the catalogue does not know is refused (`PRODUCT_NOT_FOUND`) before the account exists | app (onboarding) | `customers:link` | admin, teller |
| `GET  /v1/customer-numbering` | how customers and their accounts are numbered, and the next of each | app (onboarding) | `customers:read` | admin |
| `PUT  /v1/customer-numbering/{series}` | change a series — prefix, width, next value (forward only: a `nextValue` below the current one is refused, `COMMAND_INVALID`) | app (onboarding) | `org:manage` | admin |
| `POST /v1/products/{productId}/versions` | draft the next version, optionally copying an existing one's rules | app (pricing) | `products:create` | admin |
| `GET  /v1/products/{productId}/versions/{version}` | one version with its fee and limit rules | app (pricing) | `products:read` | admin |
| `PUT  /v1/products/{productId}/versions/{version}/fee-rules` | replace the draft's fee schedule; accounts validated against the institution's own | app (pricing) | `products:create` | admin |
| `PUT  /v1/products/{productId}/versions/{version}/limit-rules` | replace the draft's limits — without a PER_TXN rule the product refuses everything | app (pricing) | `products:create` | admin |
| `PATCH /v1/products/{productId}/versions/{version}` | schedule when the version becomes live once published | app (pricing) | `products:create` | admin |
| `POST /v1/approvals` | raise a maker-checker approval, bound to a target and amount | orchestration | `approvals:make` | supervisor |
| `POST /v1/approvals/{id}/check` | approve or reject (checker ≠ maker, enforced) | orchestration | `approvals:check` | supervisor |
| `GET  /v1/ops/cases` | unresolved-outcome cases | orchestration | `ops:read` | ops |
| `POST /v1/ops/cases/{id}/resolve` | **re-attempt resolution now.** Does not accept an outcome | orchestration | `ops:resolve` | ops |
| `GET  /v1/currencies` | what this institution deals in, and each one's ISO 4217 exponent — **not** an allow-list, the ledger is the authority on what may be posted | orchestration | `org:read` | everyone |
| `POST /v1/currencies` | offer a currency, or bring a withdrawn one back (upsert; withdrawing deactivates rather than deletes, because its accounts still have to render) | orchestration | `org:manage` | admin |
| `GET  /v1/currencies/registry` | every currency the ledger will carry, with its ISO 4217 exponent — the list an institution's own offering is chosen from | orchestration | `org:read` | admin |
| `DELETE /v1/currencies/{code}` | stop offering a currency; its existing accounts keep their balances and keep rendering | orchestration | `org:manage` | admin |
| `POST /v1/org-units` | create an organizational unit (ADR 0012) | organization | `org:manage` | admin |
| `GET  /v1/org-units` | the tenant's units | organization | `org:read` | admin, supervisor |
| `GET  /v1/org-units/{id}` | one unit | organization | `org:read` | admin, supervisor |
| `POST /v1/org-units/{id}/close` | close a unit; its history stays attributed to it | organization | `org:manage` | admin |
| `POST /v1/org-units/{id}/assignments` | assign a principal to a unit, attributed; a person's `units` claim moves with it | organization | `org:manage` | admin |
| `POST /v1/org-units/{id}/assignments/revoke` | revoke a live assignment, attributed; history kept, and a person's claim drops it | organization | `org:manage` | admin |
| `GET  /v1/org-units/{id}/assignments` | the unit's live assignments — what identity provisioning reads | organization | `org:read` | admin |
| `GET  /v1/permissions` | the platform's permission vocabulary, with what each grants | admin | `users:read` | admin |
| `GET  /v1/roles` | the tenant's roles — seeded templates and anything authored — with contents | admin | `users:read` | admin |
| `POST /v1/users` | create a member of staff with roles and units; temporary credential returned once | admin | `users:manage` | admin |
| `GET  /v1/users` | staff, filtered by role and unit, keyset-paged | admin | `users:read` | admin |
| `GET  /v1/users/{id}` | one user with roles, units and status | admin | `users:read` | admin |
| `PUT  /v1/users/{id}/units` | replace unit assignments — Core's record and the token claim together | admin | `users:manage` | admin |
| `POST /v1/users/{id}/reset-password` | fresh temporary credential, forced change, sessions revoked | admin | `users:manage` | admin |
| `POST /v1/users/{id}/unlock` | clear a lockout early | admin | `users:manage` | admin |
| `PUT  /v1/users/{id}/employment` | set the administered facts — staff number, job title, start date | admin | `users:manage` | admin |
| `POST /v1/roles` | author a tenant role; name namespaced `role:`, permissions must be ones you hold | admin | `users:manage` | admin |
| `PUT  /v1/roles/{role}/permissions` | recompose a role; refused for any permission the grantor lacks | admin | `users:manage` | admin |
| `DELETE /v1/roles/{role}` | delete a tenant-authored role; refused while held, and for templates | admin | `users:manage` | admin |
| `PUT  /v1/users/{id}/roles` | replace a user's role grants; refused if it would remove the last administrator | admin | `users:manage` | admin |
| `GET  /v1/job-titles` | the institution's job vocabulary, with how many hold each | admin | `users:read` | admin |
| `POST /v1/job-titles` | add a title to the vocabulary | admin | `users:manage` | admin |
| `DELETE /v1/job-titles/{title}` | retire a title; refused while anybody holds it | admin | `users:manage` | admin |
| `GET  /v1/staff-numbering` | the numbering rule, and the number the next hire would take | admin | `users:read` | admin |
| `PUT  /v1/staff-numbering` | change prefix, width or next value | admin | `users:manage` | admin |
| `GET  /v1/internal-accounts` | the institution's own accounts, with what each is called and for | orchestration | `accounts:read` | admin |
| `POST /v1/internal-accounts` | open one in the ledger and name it — till, fee income, suspense | orchestration | `accounts:manage` | admin |
| `POST /v1/tills` | provision a till inside a validated branch | orchestration | `tills:manage` | admin |
| `GET  /v1/tills` | the tenant's tills | orchestration | `tills:read` | admin, supervisor |
| `PUT  /v1/tills/{id}/assignment` | hand an open till to a staff member, or to nobody (`assignedTo: null`) | orchestration | `tills:manage` | admin |
| `POST /v1/tills/{id}/close` | close a till; cash cannot move through it afterwards | orchestration | `tills:manage` | admin |

**The channel is permission-gated, never free-asserted.** The channel a
transfer names selects which limit rules apply, which makes it an authorization
input — so asserting one costs a permission: `channel:api` to transact as an
API channel, `channel:teller` as a counter. An unknown channel is refused
(`COMMAND_INVALID` / `CHANNEL_INVALID`); a known one the caller's token does not
license is a 403. Cash endpoints take no channel at all: cash is counter
business, and the channel is the endpoint.

**The fee-income account is product configuration, and only that.** A fee credits
the account its fee rule names. The body still carries a `feeAccountId` and it is
now ignored entirely: it existed as the fallback for versions predating the
configuration column, which — while nothing could write that column — was in
practice the only path, and meant a caller could choose where the institution's
own income landed. Pricing is authorable now, so a product that prices a fee and
names no account is refused with `FEE_ACCOUNT_NOT_CONFIGURED` rather than posting
somewhere plausible.

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

Two consequences of taking that control seriously: a draft records its
*caller* as author — never an inferred or inherited name — because the
comparison at publish is only as honest as that field; and publish holds the
version row locked while it re-checks what it is signing, so a rule write
racing the publish waits its turn and then meets the trigger, rather than
landing unsigned in a live version. Publish also refuses a version whose fee
rules name no fee account (`PRICING_ACCOUNT_INVALID`) — that row would
otherwise surface on the money path, one stranded transaction at a time.

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
orders, holds, interest accrual. The connector seam is designed
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
| `ACCOUNT_HAS_NO_PRODUCT` | the account records no product, so nothing prices the transaction | no |
| `PRODUCT_NOT_FOUND` | no product, or no published version in effect. Also at account opening, for a code the catalogue has never heard of → 422, `details.field` | no |
| `OPERATION_NOT_PERMITTED` | the product forbids this operation for this tier or channel | no |
| `LIMIT_EXCEEDED` | per-transaction or daily limit would be breached | no — new key after the window rolls |
| `AMOUNT_INVALID` | zero, negative, or above the platform cap | no |
| `COMMAND_INVALID` | a required field is absent or malformed — see reasons | no |
| `CURRENCY_MISMATCH` | entry currency ≠ account currency — or the product prices this operation only in other currencies, which refuses rather than pricing free | no |
| `WASH_TRANSACTION` | source and destination are the same account | no |
| `TILL_NOT_OPEN` | the teller's till is not open | no |
| `FEE_EXCEEDS_DEPOSIT` | the fee would consume more than the deposit | no |
| `UNIT_NOT_FOUND` | no active organizational unit answers to that code — or it is not a branch → 422 (404 on the organization surface) | no |
| `ACCOUNT_CODE_TAKEN` | the institution already has an internal account with that code → 409. `details.code` | no |
| `FEE_ACCOUNT_NOT_CONFIGURED` | the product prices a fee and names no account to credit it to → 422 | no |
| `PRICING_ACCOUNT_INVALID` | a rule names an account the institution has not opened, has closed, opened for something else, or in another currency → 422 | no |
| `ACCOUNT_NOT_OPENED` | the customer account could not be opened as asked — unknown customer, bad currency, or the ledger refused → 422 | no |
| `UNIT_CODE_TAKEN` | the tenant already has a unit with this code; codes never recycle → 409 | no — caller bug |
| `UNIT_CODE_INVALID` | the code is not lowercase letters, digits and single hyphens between them, 1–100 characters → 422. The code is copied verbatim into the `units` claim and is permanent, so it is refused rather than stored and tidied later | no — caller bug |
| `PARENT_UNIT_NOT_FOUND` | the named parent unit does not exist, is another tenant's, or is closed → 422 | no |
| `ASSIGNMENT_EXISTS` | the principal already holds a live assignment to this unit → 409 | no |
| `ASSIGNMENT_NOT_FOUND` | no live assignment ties this principal to this unit → 404 | no |
| `INSUFFICIENT_FUNDS` | relayed from the Ledger; the account would go available < 0 | no — new key after funding |
| `IDEMPOTENCY_KEY_REUSED` | same key, different payload fingerprint | no — caller bug |
| `TRANSACTION_NOT_FOUND` | unknown saga, or another tenant's | no |
| `NOT_REVERSIBLE` | target is not `COMPLETED`, or is itself a reversal | no |
| `APPROVAL_REQUIRED` | reversal without a valid maker-checker approval reference | no — obtain approval |
| `APPROVAL_INVALID` | the approval is unapproved, already spent, or bound to a different target or amount → 403 | no — obtain approval |
| `CHECKER_IS_MAKER` | the person checking an approval is the person who raised it → 403 | no — ask somebody else |
| `ALREADY_REVERSED` | a reversal exists; the response carries its id | converge on the returned id |
| `LEDGER_REFUSED` | the Ledger refused for a reason Core does not model; its code is in `details.ledgerCode` | no |
| `LEDGER_UNREACHABLE` | the connection was refused — nothing was sent, so this is definite | yes — after backoff |
| `OUTCOME_UNKNOWN` | the outcome is not known; 503, and the saga id is returned to poll | **yes — same key** |
| `EXTERNAL_REF_TAKEN` | the tenant already numbered a customer with this reference → 409 | no — caller bug |
| `ACCOUNT_ALREADY_HELD` | that ledger account is already live-linked to a customer → 409 | no |
| `ACCOUNT_NUMBER_TAKEN` | the supplied account number is already carried by a live account → 409 | no — caller bug |
| `REASON_REQUIRED` | a tier change carried no reason → 422 | no |
| `NAME_REQUIRED` | a customer was registered with no name → 422 | no — caller bug |
| `PRODUCT_REQUIRED` | an account was linked or opened without naming its product → 422 | no — caller bug |
| `TIER_UNCHANGED` | the customer already holds that tier → 422 | no |
| `CONSENT_INCOMPLETE` | a consent record omitted its category, channel or answer → 422 | no |
| `PRODUCT_CODE_TAKEN` | the tenant already has a product with this code → 409 | no — caller bug |
| `INVALID_PRODUCT_TYPE` | not one of the supported product types → 422 | no |
| `PRODUCT_VERSION_NOT_FOUND` | no such version, or another tenant's → 404 | no |
| `VERSION_ALREADY_PUBLISHED` | that version is live already → 409 | no |
| `PUBLISHER_IS_AUTHOR` | the principal wrote this version and may not publish it → 403 | no — a colleague must publish |
| `VERSION_NOT_DRAFT` | a write against a version that is already live → 409 | no — draft the next version |
| `RULES_INVALID` | a rule set this version cannot hold; `reason` names which → 422 | no — caller bug |
| `EFFECTIVE_FROM_IN_THE_PAST` | a draft dated to become effective before it existed → 422 | no |
| `DRAFT_CONFLICT` | another draft of the same next version was created concurrently → 409 | yes — the retry drafts the version after the winner's |

`NOT_REVERSIBLE` is checked **before** the approval is examined. A transaction
that has already been reversed is refused no matter what authority accompanies
the request, so collecting signatures is never a route around it.

### Reasons

| Code | Reason | Cause | `details` |
|---|---|---|---|
| `COMMAND_INVALID` | `FIELD_REQUIRED` | a required field was absent | `field` |
| `COMMAND_INVALID` | `IDEMPOTENCY_KEY_REQUIRED` | a posting without a key is not retryable | `field` |
| `COMMAND_INVALID` | `TOO_FEW_ENTRIES` | fewer than two entries | `limit`, `supplied` |
| `COMMAND_INVALID` | `STEP_CONTAINS_SEPARATOR` | a saga step name contains `:` | `field` |
| `COMMAND_INVALID` | `DERIVED_KEY_TOO_LONG` | the derived key exceeds the Ledger's cap | `maxLength` |
| `COMMAND_INVALID` | `CHANNEL_INVALID` | not a channel this platform models | `supplied` |
| `AMOUNT_INVALID` | `AMOUNT_NOT_POSITIVE` | zero or negative amount | `field`, `supplied` |
| `AMOUNT_INVALID` | `AMOUNT_SIGN_ON_ENTRY` | an entry carried a sign; direction carries it | `supplied` |
| `LIMIT_EXCEEDED` | `PER_TRANSACTION_LIMIT` | the amount alone breaches the per-transaction limit | `limit`, `supplied` |
| `LIMIT_EXCEEDED` | `DAILY_LIMIT` | the day's reservations plus this amount breach the calendar-day limit | `limit`, `supplied` |
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
