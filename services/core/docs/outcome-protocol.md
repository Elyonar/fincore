# Core — The Outcome Protocol

**Status:** AGREED v1.16 (2026-08-08) — amendments via [`CHANGELOG.md`](CHANGELOG.md)

This document exists on its own because it is the thing this category of
software gets wrong, and burying it inside a saga document guarantees it is
skimmed. Every other guarantee in Core rests on it.

## The problem

Core sits between a transactional world (its own database, the Ledger) and a
non-transactional one (partners, networks, crashed processes). No transaction
spans that boundary. So for every outbound call there is a window in which Core
does not know whether the thing it asked for happened.

The instinct is to treat that window as failure and undo. That instinct loses
money — or creates it — because the call may have succeeded.

## Three values, never two

Every outbound step resolves to exactly one of:

| Outcome | Meaning | Permitted response |
|---|---|---|
| `SUCCESS` | The operation provably happened | Advance the saga |
| `DEFINITE_FAILURE` | The operation provably did **not** happen | Compensate, then fail the saga |
| `UNKNOWN` | Not known, and may still be true | **Retry the same idempotency key.** Never compensate |

**Compensation is legal only from `DEFINITE_FAILURE`.** That single rule is the
protocol. Everything else is how each outcome is determined.

## Classifying a Ledger response

The Ledger's own contract does half this work: any 4xx is terminal for that key,
and any timeout, connection failure or 5xx means the outcome is unknown and the
caller must retry the same key.

| What Core observes | Outcome | Why |
|---|---|---|
| `2xx` | `SUCCESS` | Committed. The response carries the transaction id |
| `4xx` (`UNBALANCED`, `INSUFFICIENT_FUNDS`, `ACCOUNT_CLOSED`, `VALUE_DATE_INVALID`, …) | `DEFINITE_FAILURE` | A rejection is total: no transaction, no entries, no balance change, no event |
| `409 IDEMPOTENCY_KEY_REUSED` | `DEFINITE_FAILURE`, **and a Core bug** | Our key derivation produced a collision with a different payload. Fail loudly; never mint a new key to route around it |
| `409 ALREADY_REVERSED` | `SUCCESS` for a reversal step | The response carries the winning reversal's id; converge on it |
| `5xx` | `UNKNOWN` | The ledger may have committed before failing to respond |
| Timeout, connection reset, TLS failure, DNS failure | `UNKNOWN` | Nothing was observed at all |
| A response that fails to parse | `UNKNOWN` | Never assume the shape of a message you could not read |

The last two rows are where implementations quietly go wrong: a client library
that raises the same exception type for "connection refused" (nothing happened)
and "read timeout" (anything may have happened) makes the distinction
unavailable to the caller. **The ledger client must preserve it** — a connection
refused *before* the request was written is a `DEFINITE_FAILURE`; everything
after is `UNKNOWN`.

## Resolving an UNKNOWN

An `UNKNOWN` is not a terminal state. It is a state with an obligation attached.

1. **Retry the same derived key**, with backoff. The Ledger's idempotency
   registry makes this always safe: if the original committed, the retry replays
   the original result; if it did not, the retry posts once.
2. Continue until a definitive answer arrives — `SUCCESS` or
   `DEFINITE_FAILURE` — or until the escalation bound is reached.
3. On reaching the bound, move the saga to `PENDING_RESOLUTION` and raise an ops
   case. **Do not compensate, do not fail, do not retry forever silently.**

A saga in `PENDING_RESOLUTION` has funds in an undetermined state and a human
owning the determination. That is an unpleasant state to be in and a far better
one than either wrong guess.

**The schedule.** Exponential backoff from 1 s, doubling with jitter, capped at
60 s. Escalate to `PENDING_RESOLUTION` after **15 minutes or 12 attempts,
whichever comes first**. The values are configuration; the shape is contract.

Aggressive early because the most likely cause of an unknown is that the ledger
committed and the response was lost — a prompt retry replays the answer rather
than doing anything new. Fifteen minutes exceeds any plausible pod restart or
database failover, and is short enough to matter while a customer is still
standing at a counter.

**Retrying stays correct even after a ledger restore.** It is tempting to think
a restore makes replay dangerous — the idempotency registry is rewound, so the
derived key may be free again, and a retry would *create* a posting rather than
confirm one. It is worth working through, because the conclusion is the
opposite:

- If the posting survived the restore, its key is still registered and the replay
  returns the original result.
- If the posting did **not** survive, the key is free and the replay posts it —
  which restores exactly the state the restore destroyed.

Both branches converge on the intended state, which is why the ledger's own
restore protocol says *"Orchestration replays its outstanding window (safe:
idempotent)"* (`services/ledger/docs/architecture.md`). Replay is the designed
recovery path, not a hazard to be guarded against.

The corollary matters: **there is no path in this design by which money moves on
a human's say-so, outside a business reversal.** Post-restore reconciliation is
mechanical — see [`saga-protocol.md`](saga-protocol.md).

For a future step whose callee offers a status query (a rails connector's
transaction status query), the resolution procedure gains a step: query the
partner for the outcome before retrying. That is strictly better than retrying
blind, and it is the reason the protocol is written in terms of *resolving* an
unknown rather than merely retrying one.

## Why two values fails, in both directions

**Collapsing `UNKNOWN` into `DEFINITE_FAILURE`.** Core compensates: it releases
the limit reservation and tells the caller the transfer failed. The ledger
posting had in fact committed. The customer's money moved and the platform's
record says it did not. Every subsequent reconciliation disagrees with the
ledger, and the customer is told a false thing about their own account.

**Collapsing `UNKNOWN` into `SUCCESS`.** Core marks the saga complete and
records a transaction id it never received. The posting never happened. The
recipient is credited in Core's story and not in the ledger's — and because the
ledger is the source of truth, the money simply does not exist.

Both failures are silent at the moment they occur and surface later as
reconciliation breaks that nobody can attribute. That is why the third value is
worth its cost.

## The obligation Core imposes on its own callers

Core is a callee as well as a caller, so it publishes the same contract it
consumes:

- Every creating request carries a caller-supplied idempotency key, unique per
  tenant.
- Any **4xx** from Core is terminal for that key. A new logical attempt mints a
  new key.
- Any **timeout, connection failure or 5xx** from Core means the outcome is
  unknown, and the caller **must retry the same key** until it receives a
  definitive answer. Never abandon an unknown outcome; never mint a new key for
  one.
- `GET /v1/transactions/{id}` exists so a crashed caller can ask what happened
  without mutating anything.

This is not symmetry for its own sake. A channel that mints a new key after a
timeout is how a double payment reaches the ledger with two perfectly valid
idempotency keys, and no downstream control can catch it.

## What this protocol does not cover

- **Local failures inside one database transaction** are not unknowns. They roll
  back; there is nothing to resolve.
- **Releasing a limit reservation** is a local compensation, not a saga step. It
  is still governed by the rule above — it may only happen once the posting is a
  `DEFINITE_FAILURE`.
