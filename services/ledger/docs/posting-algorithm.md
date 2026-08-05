# Ledger — Posting Algorithm & Concurrency

**Status:** AGREED v1.3.1 (2026-08-05) — amendments via [`CHANGELOG.md`](CHANGELOG.md)

**The global lock protocol (two tiers, one rule, everywhere):** every
operation that locks anything acquires locks in this order — **tier 1:
target transaction rows** (reversal targets, compensation targets), sorted by
transaction id; **then tier 2: balance rows**, sorted by account id. No
operation ever acquires a tier-1 lock while holding a tier-2 lock. This is
what makes "deadlocks cannot occur anywhere in the service" true rather than
asserted — a single sorted lock class is not sufficient, because reversal and
compensation both need the original transaction's row as well as balance rows,
and would otherwise acquire the two classes in opposite orders.

Everything below happens inside **one database transaction** at **READ
COMMITTED** isolation (see design.md — the algorithm depends on `FOR UPDATE`
re-reading the latest committed row after the lock is granted, and on
unique-index conflicts as the idempotency arbiter). Any failure at any step
rolls back the whole thing: complete or absent, never partial.

## Posting

1. **Idempotency check.** Look up `(tenant, idempotency_key)`.
   - Found, fingerprint matches → return the original result (replay).
   - Found, fingerprint **differs** → `409 IDEMPOTENCY_KEY_REUSED`. A caller
     attaching an old key to a new payload gets a loud error, never a silent
     wrong answer.
   - Not found → proceed.

2. **Validate shape.** 2..N entries (N = the tenant's `max_entries_per_tx`,
   platform maximum 100); per-currency Σ debits = Σ credits;
   amounts integer, `0 < amount ≤ 10^15`; no account on both debit and credit
   side (wash rejection); key ≤ 200 chars.

3. **Validate dates.** `value_date` defaults to the current business date in
   the **tenant's configured timezone** (Nigeria: Africa/Lagos — a 00:30 WAT
   deposit books to the correct business day even on a UTC server). Future
   value dates rejected. Backdated value dates require `backdate_reason`,
   must fall within the tenant's backdate window (default ≤ 30 days), and
   must not fall in a **closed accounting period**.

4. **Register the transaction.** Insert the transaction row (with
   fingerprint). The unique index is the concurrency arbiter: a racing
   duplicate blocks until the first commits, then fails with a duplicate-key
   error and re-reads the winner's result (fingerprint still checked). A
   double post is structurally impossible.

5. **Tier-1 lock: compensation target, if any.** If the posting carries
   `relatesToTransactionId`, lock that original transaction's row **now,
   before any balance row** (the global protocol). If it is REVERSED →
   `TARGET_REVERSED` — compensating an undone transaction is a double
   credit. The same tier-1 lock, taken first by reversal too, makes the
   exclusion race-safe *and* deadlock-free: both operations acquire the
   transaction row before any balance row, never the other way around.

6. **Tier-2 lock: balances deterministically.** `SELECT … FOR UPDATE` on the
   balance rows of all touched accounts in **sorted account-id order** — the
   protocol used by *every* operation that touches balance rows (postings,
   holds, releases, captures, expiry sweeps, closures). Validate: accounts
   exist, tenant matches (also structurally guaranteed by composite FKs),
   status OPEN, entry currency = account currency.

7. **Consume a hold, if requested.** A posting may carry `consumeHoldId`:
   the hold must be ACTIVE, on a touched account, currency-matched, and the
   debit against that account must be ≤ the hold amount. The hold flips to
   CONSUMED and `holds_total` decreases **in this same transaction** — capture
   is atomic. There is no release-then-post window in which concurrent
   spending can strand an externally-settled obligation.
   Capture is **single-shot, decided**: a capture below the hold amount
   consumes the hold and explicitly releases the remainder (event carries
   both amounts); multi-capture is not supported in v1. Reversing a
   hold-consuming transaction restores balances, never reservations — the
   hold stays CONSUMED.

8. **Write entries; update balances.** Apply per-account net deltas
   (credit-positive). Negative-balance guard: `allow_negative = false`
   accounts must keep `available ≥ 0`.

9. **Write the outbox event; commit.** Same transaction — an event exists iff
   the posting committed.

## Reversal

- Locks the original (**tier 1**, before any balance row — the same order a
  compensation posting uses, so the two serialize instead of deadlocking);
  only status POSTED can be reversed, **exactly once** (partial unique
  index). A concurrent second reversal attempt fails with
  `409 ALREADY_REVERSED` carrying the winning reversal's id, so sagas
  converge instead of retry-looping.
- **A reversal's target must not itself be a reversal** (schema-enforced).
  Corrections beyond one undo are fresh transactions with new keys.
- If the original carries compensations (transactions with
  `relates_to_transaction_id` pointing at it), plain reversal is rejected —
  partial refund + full reversal must never double-credit.
- Mirrored entries carry the **current business date** as value_date (posting
  into the past would rewrite closed/backdated periods); the link to the
  original preserves traceability.
- Reversals bypass **two** guards, deliberately: the negative-balance guard
  and the CLOSED-account check — undoing a posting must always be possible,
  even into an account closed since. Both bypasses are recorded and surface
  in the **authorized-exposure report**, not as invariant violations (see
  testing.md — this distinction keeps the invariant alarm meaningful).

## Holds

- **Placement** is idempotent (unique key), locks the balance row, requires
  `available ≥ amount` on protected accounts, `expires_at` NOT NULL within
  the tenant's max TTL.
- **Release** responses distinguish outcomes precisely:
  `RELEASED_NOW | ALREADY_RELEASED | ALREADY_EXPIRED | ALREADY_CONSUMED` — a
  caller whose reservation silently expired must *know*, not receive a
  success-shaped no-op while the funds it thinks are reserved get spent.
- **Capture** is `consumeHoldId` on a posting (above) — never
  release-then-post.
- **Expiry sweep:** small batches; per hold: acquire the balance row lock
  (same sorted protocol), flip ACTIVE → EXPIRED, decrement `holds_total`, and
  write `hold.released(reason=expired)` to the outbox — all in one database
  transaction per batch. Never a bulk UPDATE outside the lock protocol.

## Account closure — and the reversal-residue release valve

`POST /v1/accounts/{id}/close`: acquires the balance row lock (serializing
against in-flight postings), verifies balance = 0 and no ACTIVE holds,
records `closed_by`/`closed_at`, emits `account.closed`. Residual-balance
accounts cannot be closed — sweep first via a normal posting. No reopen.

Because reversals may post into CLOSED accounts, a closed account can end up
with a nonzero balance — **in either direction** — that ordinary postings
(`ACCOUNT_CLOSED`) could never extract. Positive residue: reversal credits a
closed account. Negative residue: the erroneous-credit dispute path — credit
in error, customer withdraws, account closed at zero, original reversed →
closed account sits negative (reversals bypass both the CLOSED check and the
negative guard). The release valve is therefore **direction-neutral**: a
posting flagged `closedAccountSweep` may post the single entry — **debit or
credit, whichever direction zeroes the balance** — that brings the CLOSED
account exactly to zero, with a same-tenant SUSPENSE-type counterparty;
anything else is `SWEEP_INVALID`. The account stays CLOSED throughout.
Both directions are covered in the test suites.

## Hot-account contention (designed for, not discovered later)

Fee and settlement-mirror accounts appear in a large fraction of postings; a
row lock makes each a serialization point. The designed mitigation is
**fan-in sub-accounts**: a hot internal account is provisioned as N
sub-accounts sharing a `group_ref`; writers pick one (hash/round-robin),
reporting reads the group via `GET /v1/account-groups/{groupRef}/balance`.

Sharding is restricted to **`allow_negative = true` accounts only** — that
restriction is what makes it invariant-neutral. A guarded account must never
be sharded: the negative-balance guard is per-row, so a group with ample
aggregate funds could still reject a debit because the hashed shard happens
to be short. Unguarded internal accounts have no such check, so per-account
invariants hold unchanged and the group view is pure reporting. (Guarded
hot accounts — tills, agent floats — scale by being naturally per-till and
per-agent, not by sharding.)

The trigger to deploy fan-in is objective: CI carries a
**single-hot-account throughput benchmark** with a floor (see testing.md);
if the floor fails on reference hardware, sharding is applied before launch,
not after an incident.

## Why this holds up under concurrency

- The unique index (not code) arbitrates idempotency races — for postings,
  holds, *and* account creation.
- One global two-tier lock protocol (transaction rows by id, then balance
  rows by account id) covers every lock-taking operation — reversals and
  compensations included.
- Fingerprints make key-reuse loud; consume-holds make capture atomic;
  distinct release outcomes make expiry races visible.
- The failure contract is uniform: rejected operations leave zero rows, and
  "same key → same result" binds committed operations only — callers treat
  any 4xx as terminal for that key and mint a new key per logical attempt.

## Performance envelope

Posting commit p99 < 200 ms; 200 TPS sustained per tenant cluster with a
horizontal path beyond. Durability: the ledger database runs **synchronous
replication — RPO = 0 for acknowledged commits**. An "RPO ≤ 5 min" posture is
acceptable elsewhere in the platform, never here: five minutes of lost
committed postings *is* money being wrong, with already-published events
"proving" states the ledger would then deny (restore protocol: architecture.md).
