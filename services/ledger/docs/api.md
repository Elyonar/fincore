# Ledger — API Surface (v1)

**Status:** AGREED v1.7 (2026-08-06) — amendments via [`CHANGELOG.md`](CHANGELOG.md)

REST/JSON. OpenAPI spec will be generated from code once implementation
lands; this document is the agreed contract shape.

**Glossary:** `available = current_minor − holds_total_minor`. This is the
quantity the negative-balance guard, `INSUFFICIENT_FUNDS`, and Invariant 4
operate on.

## Endpoints

| Method & path | Purpose | Allowed caller |
|---|---|---|
| `POST /v1/accounts` | create account (idempotency key required) | Orchestration/admin |
| `POST /v1/accounts/{id}/close` | close account (balance 0, no active holds) | Orchestration/admin |
| `GET  /v1/accounts/{id}` | account + balance (current, holds, available) | Orchestration, Reporting |
| `GET  /v1/accounts/{id}/entries` | statement for a period: opening + lines + closing, final or interim (see below) | Orchestration, Reporting |
| `GET  /v1/accounts/{id}/holds` | holds on an account (filter by status) | Orchestration, Reporting |
| `GET  /v1/account-groups/{groupRef}/balance` | summed balance across a fan-in shard group | Orchestration, Reporting |
| `POST /v1/transactions` | post a balanced transaction; may atomically consume a hold; may sweep a closed account's reversal residue | **Orchestration only** |
| `GET  /v1/transactions/{id}` | transaction + entries | Orchestration, Reporting |
| `POST /v1/transactions/{id}/reverse` | reverse a posted transaction (own key) | **Orchestration only** |
| `POST /v1/holds` | place hold (idempotency key + TTL required) | **Orchestration only** |
| `GET  /v1/holds/{id}` | read a hold's current state — crash recovery reads, never mutates | Orchestration, Reporting |
| `POST /v1/holds/{id}/release` | release hold (outcome-precise) | **Orchestration only** |
| `GET  /v1/periods` | list accounting periods and their close state | ops, Reporting |
| `POST /v1/periods/{end}/close` | close an accounting period (attributed; maker-checker upstream; no reopen) | ops/admin |
| `GET  /v1/invariants` | fetch latest completed invariant report | ops, monitoring |
| `POST /v1/invariants/run` | request a run (202; queued; rate-limited) | ops |

`GET /v1/invariants` only **fetches** — an endpoint that could trigger
full-history scans on demand would be a denial-of-service lever pointed at
the ledger's own database.

## The statement contract (a period document, not a feed)

`GET /v1/accounts/{id}/entries` serves **statements for a requested period**,
modelled on how the industry actually issues them (ISO 20022 `camt.053`,
SWIFT MT940) rather than as a cursor over live data:

- **A statement is bounded and reconciles.** The response carries the period
  bounds, an **opening balance**, the lines, and a **closing balance**, and
  `opening + Σ movements = closing`. That reconciliation is the statement's
  own proof of integrity — the same property `:60F:`/`:62F:` carry in MT940
  and `OPBD`/`CLBD` in camt.053.
- **Every line carries both dates** — `bookedAt` (when the ledger recorded
  it) and `valueDate` (when it counts). Lines are ordered by
  `(value_date, id)`, so a backdated item sorts into business order and reads
  correctly because its booking date is displayed beside it.
- **Closed periods are final; the open period is interim.** A statement whose
  period is closed (`accounting_periods`) is immutable *by construction* — no
  posting can land in a closed period, so re-requesting it returns a
  byte-identical document forever, with no snapshot machinery required. A
  statement covering the still-open period is labelled **interim** and may
  change. The standards draw exactly this line: camt.052 (interim report) vs
  camt.053 (statement), MT942 vs MT940.
- **Late items land on the next statement.** A backdated posting arriving
  after its period closed appears on the current statement carrying its
  earlier value date. It never edits a statement already issued — which is
  the entire reason cutoffs exist.

- **Lines are paged; the period is not.** A busy account's yearly statement is
  not a response you want to build in memory, so lines come back in pages of at
  most 1000 (default 500), with `nextCursor` set while more remain. `opening`,
  `closing` and the period bounds describe the **whole period** and are
  identical on every page — they are the document's header, not a running
  total — so `opening + Σ movements across all pages = closing`.

This is also why the endpoint needs no *pinned* cursor: period close does the
work a snapshot would otherwise have to.

**Paging a document is not the same thing as a change feed**, and conflating the
two is the mistake this section exists to prevent. A page cursor walks the lines
of one bounded period, ordered by `(value_date, id)`, and is spent when that walk
ends. A change-feed cursor is *durable across time* — "give me everything since
last night" — and that is what is forbidden below.

It is correspondingly **forbidden to use this endpoint as an incremental
change feed** (holding a durable cursor and polling for "entries since id
N"). Entry ids are assigned at insert, not commit — a slow posting can commit
id=100 *after* id=105 was already read, and a cursor-holding consumer skips it
forever. That is the same failure the outbox relay contract exists to prevent
(architecture.md), and the outbox **is** the change feed: incremental
consumers, Reporting ingestion included, subscribe to events and fetch state
via the read API. The invariant anchors close the same insert-vs-commit gap
with an MVCC quiesce horizon (testing.md).

## Contract properties

- **Idempotency everywhere — including holds and account creation.** Every
  creating request carries a caller-supplied key (≤ 200 chars), unique per
  tenant. Replays return the original result.
- **Key reuse is loud.** Same key + different payload fingerprint →
  `409 IDEMPOTENCY_KEY_REUSED`. Never a silent wrong answer.
- **The retry rule — both halves, binding on Orchestration.** Any **4xx** is
  terminal for that key: a new logical attempt mints a new key. Any
  **timeout, connection failure, or 5xx** means the outcome is *unknown* and
  the caller **must retry the same key** (with backoff) until it receives a
  definitive 2xx or 4xx — never abandon an unknown outcome, never mint a new
  key for one (that is how double posts happen upstream). Idempotent replay
  makes this retry always safe.
- **The registry binds committed operations only.** A rejected request leaves
  zero rows and no key registration.
- **Fingerprint canonicalization (defined).** The request fingerprint is a
  SHA-256 over the canonical economic content **of the request as received**:
  tenant, sorted entries (accountId, direction, amountMinor, currency,
  valueDate), `consumeHoldId`, `relatesToTransactionId`, and the sweep flag.
  Two rules make it safe:
  - **`description` and `initiatedBy` are excluded** — a legitimate retry of
    the same operation from a different pod or user session must replay, not
    409. Two requests that move the same money identically are the same
    request; prose is not economics.
  - **Omitted optional fields canonicalize as absent, never as server-resolved
    defaults.** This matters most for `valueDate`: it defaults to the tenant's
    current business date, so fingerprinting the *resolved* value would give a
    mandated same-key retry that crosses the tenant's midnight a different
    fingerprint, reject it as `IDEMPOTENCY_KEY_REUSED` — a 4xx, therefore
    terminal — and push the caller into minting a new key for an operation
    that may already have committed. The retry rule and the fingerprint rule
    have to agree, or the pair produces the double post each was written to
    prevent.
- **Rejections are total.** No transaction, no entries, no balance change,
  no event.
- **Amounts are bounded integers; balances are not — serialization differs.**
  Request amounts: `0 < amountMinor ≤ 10^15` (below both BIGINT-sum overflow
  and 2^53 — exact as a JSON number; strict-long parsing tested). But
  **balance fields are unbounded sums** (`currentMinor`, `holdsTotalMinor`,
  `availableMinor`, and especially group balances, which sum across shards)
  and can legitimately exceed 2^53 — so **all monetary fields in responses
  and in outbox event payloads are serialized as decimal strings**
  (`"currentMinor": "12000000000000000"`), and requests accept numbers or
  strings. One rule, applied everywhere JSON leaves this service, so no
  consumer ever silently loses precision.
- **Transaction limits.** 2..N entries, where N is the tenant's
  `max_entries_per_tx` (default and platform maximum 100); no account on both
  debit and credit side; entry currency = account currency.
- **Value dates.** Default: current business date in the tenant's configured
  timezone (`tenant_config`). Future dates rejected; backdating bounded
  (window + `backdateReason`; closed periods rejected).
- **Closed-account sweep — direction-neutral.** A posting flagged
  `closedAccountSweep: true` may post the single entry — **debit or credit,
  whichever zeroes the balance** — that brings a CLOSED account exactly to
  zero, with a same-tenant SUSPENSE-type counterparty. This covers both
  residue directions: reversal credit into a closed account (positive) and
  the erroneous-credit dispute path (reversal drives a drained closed
  account negative). Anything else touching a closed account (non-reversal)
  is `ACCOUNT_CLOSED`; a sweep that doesn't exactly zero, or lacks the
  suspense counterparty, is `SWEEP_INVALID`.
- **Tenant scoping is implicit** and structurally enforced (composite FKs).
  Not-found and wrong-tenant are deliberately indistinguishable.

## Error catalog (distinct, documented, tested)

Every rejection returns the shape defined in
[`docs/conventions/error-contract.md`](../../../docs/conventions/error-contract.md):

```json
{
  "code": "LIMIT_EXCEEDED",
  "reason": "ENTRY_COUNT_EXCEEDED",
  "message": "a transaction may carry at most 1000 entries",
  "retryableWithSameKey": false,
  "details": { "limit": "1000", "supplied": "1400" }
}
```

`code` is what a caller branches on. `reason` distinguishes causes that share a
code. `details` carries the facts a translated message interpolates. **`message`
is developer English for a log** — never displayed to an end user, never parsed,
and free to be reworded without an amendment. A channel serving a francophone
tenant renders its own string from `code`, `reason` and `details`; it must never
forward `message`.

| Code | Meaning | Retry with same key? |
|---|---|---|
| `UNBALANCED` | per-currency Σdebits ≠ Σcredits, or < 2 entries | no — terminal |
| `WASH_TRANSACTION` | an account appears on both sides | no |
| `LIMIT_EXCEEDED` | a bound was broken or a required field was absent — see reasons | no |
| `ACCOUNT_NOT_FOUND` | unknown account, tenant, transaction or hold (or another tenant's) | no |
| `ACCOUNT_CLOSED` | posting touches a closed account (non-reversal, non-sweep) | no |
| `SWEEP_INVALID` | closedAccountSweep that doesn't zero the account or lacks a suspense counterparty | no |
| `CURRENCY_MISMATCH` | entry/hold currency ≠ account currency | no |
| `INSUFFICIENT_FUNDS` | protected account would go available < 0 | no — new key after funding |
| `IDEMPOTENCY_KEY_REUSED` | same key, different payload fingerprint | no — caller bug |
| `VALUE_DATE_INVALID` | the value date or period is not postable — see reasons | no |
| `ALREADY_REVERSED` | reversal exists; response carries its id in `detail` | converge on returned id |
| `REVERSAL_OF_REVERSAL` | target is itself a reversal | no — post a fresh correction |
| `HAS_COMPENSATIONS` | target has linked compensations; plain reversal blocked | no — resolve via ops |
| `TARGET_REVERSED` | compensation links a REVERSED transaction — the double-credit mirror of HAS_COMPENSATIONS | no |
| `HOLD_NOT_ACTIVE` | consume on a hold that is RELEASED / EXPIRED / CONSUMED | no — re-authorize |
| `HOLD_EXCEEDED` | debit on held account > hold amount | no |
| `CLOSE_BLOCKED` | closure with nonzero balance or active holds | no — sweep/release first |
| `PERIOD_CLOSED` | period-close on an already-closed period | no |
| `RATE_LIMITED` | the caller exceeded its request budget | yes — after backoff |

### Reasons

Four codes cover several distinct causes each. A caller that renders user-facing
text branches on the reason, not on the message.

| Code | Reason | Cause | `details` |
|---|---|---|---|
| `LIMIT_EXCEEDED` | `FIELD_REQUIRED` | a required field was absent | `field` |
| `LIMIT_EXCEEDED` | `AMOUNT_NOT_INTEGER` | amount given as a decimal; money is integer minor units | `field`, `supplied` |
| `LIMIT_EXCEEDED` | `AMOUNT_NOT_PARSEABLE` | amount is not an integer at all | `field`, `supplied` |
| `LIMIT_EXCEEDED` | `AMOUNT_NOT_POSITIVE` | amount is zero or negative | — |
| `LIMIT_EXCEEDED` | `AMOUNT_ABOVE_CAP` | amount exceeds the 10^15 minor-unit cap | `cap`, `supplied` |
| `LIMIT_EXCEEDED` | `ENTRY_COUNT_EXCEEDED` | more entries than a transaction may carry | `limit`, `supplied` |
| `LIMIT_EXCEEDED` | `IDEMPOTENCY_KEY_TOO_LONG` | key outside 1..200 characters | `maxLength` |
| `LIMIT_EXCEEDED` | `HOLD_EXPIRY_REQUIRED` | a hold was submitted without an expiry | — |
| `UNBALANCED` | `TOO_FEW_ENTRIES` | fewer than two entries | `minimum`, `supplied` |
| `UNBALANCED` | `CURRENCY_NOT_BALANCED` | debits ≠ credits in one currency | `currency`, `differenceMinor` |
| `UNBALANCED` | `ENTRIES_REQUIRED` | no entries supplied | — |
| `VALUE_DATE_INVALID` | `VALUE_DATE_IN_FUTURE` | value date after the business date | `valueDate`, `businessDate` |
| `VALUE_DATE_INVALID` | `BACKDATE_WINDOW_EXCEEDED` | older than the tenant's backdate window | `valueDate`, `earliestAllowed`, `windowDays` |
| `VALUE_DATE_INVALID` | `BACKDATE_REASON_REQUIRED` | backdated posting without a stated reason | — |
| `VALUE_DATE_INVALID` | `PERIOD_CLOSED` | value date falls in a closed accounting period | `valueDate` |
| `VALUE_DATE_INVALID` | `PERIOD_ALREADY_CLOSED` | close requested on an already-closed period | — |
| `VALUE_DATE_INVALID` | `STATEMENT_PERIOD_INVALID` | statement `from` after `to`, or range unusable | — |
| `VALUE_DATE_INVALID` | `CURSOR_MALFORMED` | statement page cursor cannot be decoded | — |
| `ACCOUNT_NOT_FOUND` | `UNKNOWN_ACCOUNT` | no such account for this tenant | — |
| `ACCOUNT_NOT_FOUND` | `UNKNOWN_TENANT` | tenant not provisioned, or not ACTIVE | — |
| `ACCOUNT_NOT_FOUND` | `UNKNOWN_TRANSACTION` | no such transaction for this tenant | — |
| `ACCOUNT_NOT_FOUND` | `UNKNOWN_HOLD` | no such hold for this tenant | — |
| `CLOSE_BLOCKED` | `ACCOUNT_ALREADY_CLOSED` | the account is already closed | — |
| `CLOSE_BLOCKED` | `BALANCE_NOT_ZERO` | closure attempted with a nonzero balance | — |
| `CLOSE_BLOCKED` | `ACTIVE_HOLDS_PRESENT` | closure attempted with holds still active | — |
| `SWEEP_INVALID` | `SWEEP_NOT_SINGLE_CLOSED_ACCOUNT` | sweep does not target exactly one closed account | — |
| `SWEEP_INVALID` | `SWEEP_DOES_NOT_ZERO` | sweep leaves a residual balance | — |
| `SWEEP_INVALID` | `SWEEP_COUNTERPARTY_NOT_SUSPENSE` | sweep counterparty is not a suspense account | — |
| `HOLD_NOT_ACTIVE` | `HOLD_ALREADY_RESOLVED` | hold is RELEASED, EXPIRED or CONSUMED | — |
| `HOLD_NOT_ACTIVE` | `HOLD_NOT_ON_TOUCHED_ACCOUNT` | the named hold is not on an account this posting touches | — |

Deliberately absent: `ACCOUNT_NOT_FOUND`'s reasons never distinguish "does not
exist" from "belongs to another tenant". That indistinguishability is the point
— a caller must not be able to probe for another tenant's accounts — and no
reason will ever be added that breaks it.

`ErrorCodeCatalogTest` fails the build if a code or reason exists in the source
and not in these tables, or the reverse.

## Hold release — outcome-precise responses

`POST /v1/holds/{id}/release` returns the transition that actually happened:
`RELEASED_NOW`, `ALREADY_RELEASED`, `ALREADY_EXPIRED`, or `ALREADY_CONSUMED`.
For non-mutating recovery, use `GET /v1/holds/{id}` — a crashed orchestrator
asks what state its hold is in; it does not have to mutate to find out.

Capture is single-shot: consuming a ₦10,000 hold with a ₦6,000 debit consumes
the hold and explicitly releases the ₦4,000 remainder — the `hold.released`
event carries `capturedMinor` and `releasedRemainderMinor`.

## Example: post a transaction (consuming a hold)

```json
POST /v1/transactions
{
  "idempotencyKey": "orch-7f3a-2026-08-03-000124",
  "initiatedBy": "user:ada.o@branch-01",
  "description": "NIBSS outbound transfer settlement",
  "consumeHoldId": "9f6a3c1e-…",
  "entries": [
    { "accountId": "…customer…",   "direction": "DEBIT",  "amountMinor": 500000, "currency": "NGN" },
    { "accountId": "…settlement…", "direction": "CREDIT", "amountMinor": 500000, "currency": "NGN" }
  ]
}
```

The hold is consumed and the entries committed in one database transaction.
Replaying the same body returns the same response; replaying the key with a
different body returns `409 IDEMPOTENCY_KEY_REUSED`.
