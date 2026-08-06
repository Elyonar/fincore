# Convention — the error contract

Every fincore service rejects requests in the same shape, and that shape is
designed so a caller can tell a user what went wrong **in the user's language**.

This exists because of a concrete failure. The ledger once answered a decimal
amount with:

```json
{ "code": "LIMIT_EXCEEDED", "message": "amountMinor must be an integer count of minor units (kobo), not a decimal" }
```

`LIMIT_EXCEEDED` covered eight unrelated failures — a missing field, a decimal
amount, an unparseable amount, an amount over the cap, a non-positive amount,
too many entries, an over-long idempotency key, a hold without an expiry. The
only thing distinguishing them was an English sentence. A bank in Abidjan
serving francophone customers could therefore say no more than "invalid
request", because everything specific was in prose it could not translate and
must not parse.

**The service knows what is wrong. Only the channel knows the language.** The
contract's job is to carry the first across that gap without pretending to the
second.

## The shape

```json
{
  "code": "LIMIT_EXCEEDED",
  "reason": "ENTRY_COUNT_EXCEEDED",
  "message": "a transaction may carry at most 1000 entries",
  "retryableWithSameKey": false,
  "details": { "limit": "1000", "supplied": "1400" }
}
```

| Field | Audience | Stability |
|---|---|---|
| `code` | machine | contract — changing one is MAJOR |
| `reason` | machine | contract — changing one is MAJOR |
| `details` | machine | contract — removing a key is MAJOR, adding one is MINOR |
| `message` | a developer reading a log | **none** — reword freely, no amendment |
| `retryableWithSameKey` | machine | contract |

### `code` — what a caller branches on

Coarse and stable. A caller's `switch` should stay short; a code per failure
site would make the catalog unusable.

### `reason` — what a caller renders from

Present whenever one code covers causes a user-facing message would word
differently. Absent when the code is already unambiguous — `INSUFFICIENT_FUNDS`
means one thing and needs no refinement.

The test for whether a reason is needed: *would a French translation of these
two failures be the same sentence?* If not, they need different reasons.

### `details` — the facts a sentence interpolates

Machine-readable parameters only: a field name, a limit, what was actually
supplied. **Never prose, never a pre-built sentence, never anything locale-specific.**
`{"limit": "1000"}` is right; `{"hint": "try fewer entries"}` is not — that is a
message wearing a map's clothing.

Values are strings so the contract does not depend on JSON number precision,
which is the same reason money is an integer string on the wire.

### `message` — English, for developers, never displayed

The single most important rule here:

> **A channel must never show `message` to an end user and must never parse it.**

It exists so an engineer reading a log at 2am sees what happened without
cross-referencing a table. It is not part of the contract: it may be reworded,
expanded, or made more specific in any release without an amendment. Anything a
client actually depends on belongs in `code`, `reason` or `details`.

A service cannot write end-user text and should not try. It does not know the
locale, the channel, or whether the reader is a teller with a terminal or a
customer on a USSD string with 182 characters to work with.

## How a channel renders a message

The client owns a message table keyed by `code` and `reason`, and interpolates
`details`:

```properties
# fr
LIMIT_EXCEEDED.ENTRY_COUNT_EXCEEDED = Une transaction ne peut contenir plus de {limit} écritures.
LIMIT_EXCEEDED.AMOUNT_NOT_INTEGER   = Le montant « {field} » doit être un entier en unités mineures.
INSUFFICIENT_FUNDS                  = Solde insuffisant.
```

Lookup falls back from `code.reason` to `code`, so a client that has not yet
added a string for a new reason degrades to the general message rather than
failing. **Never fall back to `message`** — an English sentence shown to a
francophone customer is worse than a general one in their own language.

## Rules

1. **A code plus its reason and details must fully determine what went wrong.**
   If rendering a correct message requires reading `message`, the contract is
   incomplete — add a reason or a detail key.
2. **Every code and reason is documented in the service's `api.md`**, with its
   meaning, its `details` keys, and whether the operation is retryable with the
   same idempotency key. Enforced by a test (see below).
3. **Codes and reasons are contract; renaming one is a MAJOR amendment** under
   [`design-changes.md`](design-changes.md). Adding a reason to a code that had
   none is **MINOR** — it refines, it does not change meaning — provided
   existing clients keying on the code alone still work.
4. **`details` carries no prose and no locale-dependent formatting.** No dates
   formatted for humans, no thousands separators, no currency symbols. Send
   `1000` and `NGN`; the channel decides whether that is `₦1 000,00` or
   `NGN1,000.00`.
5. **Do not leak across the tenant boundary to be more specific.** Where the
   ledger returns `ACCOUNT_NOT_FOUND` for both "no such account" and "another
   tenant's account", that indistinguishability is deliberate and outranks
   precision. Precision that lets a caller probe for another tenant's data is a
   vulnerability, not a feature.
6. **Denials name no permissions.** An authorization failure returns that it was
   denied, never which role or scope would have worked — that is a map handed to
   an attacker.
7. **`retryableWithSameKey` is stated, not inferred.** A caller must not have to
   deduce retry semantics from the HTTP status.

## Guardrail

Each service carries a test asserting its catalog and its code agree in both
directions:

- every error code and reason in the source appears in `api.md`
- every code-shaped token documented in `api.md` exists in the source
- the check is not vacuous — an empty enum would otherwise pass silently

The ledger's is
`services/ledger/src/test/java/.../architecture/ErrorCodeCatalogTest.java`. Copy
it when starting a service; it is listed in
[`service-scaffold.md`](service-scaffold.md).

The second direction matters as much as the first. A documented code that no
longer exists is worse than an undocumented one: somebody writes a French
message for a rejection that can never occur and trusts a table that is lying.

Non-error vocabulary — statuses, outcomes, entry directions — is derived from
the enums that define it rather than from a hand-kept allowlist, so adding a
status never has to be registered in two places.

## Why not HTTP status codes alone

A status says how the transport should behave, not what happened. `422` covers
every malformed posting the ledger can reject; `409` covers every state
conflict. Neither carries enough for a caller to explain itself to a user, and
neither is stable enough to key messages off — the same failure could
justifiably move between `400` and `422` without its meaning changing.

Status codes stay meaningful and remain the retry signal. They are not the
error contract.

## Why not translate on the server

Tempting, and wrong for three reasons:

- **The service does not know the audience.** The same rejection reaches an
  internal ops console, a partner's API integration, and a customer's phone.
  One sentence cannot serve all three.
- **Locale would become a request parameter on every write path**, including
  paths where the caller is a machine with no locale at all.
- **Translations would live in the wrong repository.** A bank adding Yoruba or
  Wolof would have to change the ledger — a money-critical service under
  invariant tests — to add a string. The channel owning its own strings is the
  boundary that lets that be a text file.

The server's obligation is to be **precise and machine-readable**. Being
understandable is the channel's, and it is the only party equipped for it.
