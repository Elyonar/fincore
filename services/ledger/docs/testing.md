# Ledger — Invariants & Test Strategy

**Status:** AGREED v1.3 (2026-08-05) — amendments via [`CHANGELOG.md`](CHANGELOG.md)

The test suite is the product's argument for correctness — public, runnable
by anyone. If you want to contribute: **try to break the ledger and encode
the attack as a test.**

## The invariants

Checked per tenant and per currency:

1. **Money is conserved.** Σ all debits = Σ all credits.
2. **The cache is honest.** Every stored balance = Σ of its entries.
3. **Holds add up.** Every `holds_total` = Σ of its ACTIVE holds.
4. **No *unexplained* negatives.** No `allow_negative = false` account has
   `available < 0` — **unless** the negative is fully explained by a
   reversal (the authorized bypass). Explained cases are *not* violations:
   they flow to the **authorized-exposure report** (account, amount, causing
   reversal, age), which has an owner and an aging SLA. This split is what
   keeps the invariant alarm meaningful: a violation page means *bug*, always,
   so nobody ever learns to ignore it. The explanation itself is provable —
   a negative without a reversal in its causal chain is a violation.
5. **Reversals are exact and exclusive.** Every REVERSED transaction has
   exactly one reversing transaction; no reversal targets another reversal;
   **no REVERSED transaction has linked compensations — ever, in either
   temporal order.** Any weaker wording — "compensations that predate its
   reversal" — would license compensating an already-undone transaction, which
   is the same double credit through the back door.
6. **Terminal states are terminal.** No RELEASED/EXPIRED/CONSUMED hold ever
   transitions again; no CLOSED account has post-closure entries except via
   reversals **and the residue sweeps they necessitate** (a sweep exactly
   zeroes the account, counterparty SUSPENSE — any other post-closure entry
   is a violation).

Platform success metric: **zero invariant violations in production, ever** —
now achievable *and* meaningful, because routine authorized exposure is
classified separately.

## How invariants are checked at scale

Full sums over 7 years of entries cannot run hourly on the operational
primary forever (~17M entries/day at 200 TPS). The design is **incremental
verification**: immutable daily balance anchors per account (each proven once
when written), hourly checks verifying `anchor + Σ(entries since anchor) =
current balance` and conservation over the delta window. Full-history proofs
run weekly against a read replica, never the primary. `GET /v1/invariants`
fetches the latest completed report; `POST /v1/invariants/run` queues a run
(202, rate-limited) — no endpoint can trigger a full scan synchronously.

**Anchors are physical, not business-dated — specified precisely:**

- An anchor keys on **entry insertion order** (an entry-id upper bound), never
  on `value_date`. "Daily" means *captured* daily; it is a checkpoint of
  physical history, not a business-date balance.
- **Backdating therefore cannot falsify an anchor:** a backdated posting is a
  *new* physical entry above every existing anchor bound — it lands in the
  current delta window like any other entry. Anchors and value dates live on
  deliberately different axes (see data-model.md, "Physical order vs business
  order"). As-of-date balances are a read-model concern and never feed
  invariants.
- **Capture uses an MVCC quiesce horizon** — the same insert-vs-commit gap
  that bans watermark relays (architecture.md) and change-feed statement
  cursors (api.md) applies here: an anchor's id bound is finalized only once
  every write transaction older than the capture snapshot has completed, so
  no late-committing entry can ever fall below an anchor's bound. One rule,
  stated once, applied in all three places.
- **The mechanism, not just the principle.** "Every older writer has
  finished" is read from PostgreSQL directly:
  `pg_snapshot_xmin(pg_current_snapshot())` gives the oldest transaction id
  still in flight. An anchor bound may only advance to cover entries whose
  inserting transactions are all strictly below that value; entries at or
  above it are left to the next capture. The horizon therefore lags live
  traffic slightly and never overtakes it — which is the required direction
  of error.
- **This is spiked before it is depended on.** Three separate guarantees rest
  on this single behaviour (anchors, the relay, and statements if they gain a
  pinned `asOf`), so it is built and proven against real PostgreSQL under
  concurrent long-running writers *first*, as a standalone exercise, rather
  than discovered to be subtly wrong underneath three features at once.

## The suites (all gate merges — no green, no merge)

**Invariant suite.** All six checks after every test scenario.

**Property-based tests.** Random operation sequences — postings, reversals,
holds, releases, captures, expiries, closures, **compensation+reversal mixes
in both orders**, closed-account sweeps, wash attempts — must preserve every
invariant; shrinking on failure.

**Lifecycle scenarios (each a named test):**
trapped-funds drill **in both directions** (positive residue: reverse into
closed → debit-sweep to suspense; negative residue: erroneous-credit dispute
path — credit in error → withdraw → close → reverse → closed account
negative → credit-sweep to zero; account CLOSED throughout both);
compensate-after-reversal rejected (`TARGET_REVERSED`) including the
concurrent race; **lock-order proof** (reversal of T racing a compensation
targeting T across shared accounts, hammered in CI — asserts *zero deadlock
aborts*, not just correct exclusion: both take the tier-1 transaction lock
before any tier-2 balance lock); partial capture (₦10k hold, ₦6k capture →
CONSUMED + explicit ₦4k remainder release, both amounts in the event);
reversal of a hold-consuming transaction (hold stays CONSUMED);
statement-vs-late-commit (the outbox feed delivers what a cursor would have
skipped); sharded-group behaviour (guard per-shard only on unguarded
accounts; group balance sums correctly **and serializes as a decimal string
beyond 2^53**).

**Contract-precision suite.** Fingerprint canonicalization —
same economic payload with different `description`/`initiatedBy` replays
(no 409); **omitted `valueDate` replays across the tenant's midnight boundary**
(the fingerprint is taken over the request as received, so a mandated same-key
retry at 00:00:01 WAT replays the 23:59:59 posting instead of 409-ing and
pushing the caller toward a new key — the retry rule and the fingerprint rule
must not be able to contradict each other); different entry order, same
entries → same fingerprint; changed
amount → 409. Retry-rule conformance: 5xx/timeout → same-key retry replays;
4xx → same key rejected as terminal. Monetary serialization: response
fields are decimal strings, request parsing accepts numbers ≤ 10^15 and
strings, rejects floats. Currency exponent: NGN(2)/JPY(0) render and
validate from the `currencies` table, never from hardcoded assumptions.

**Statement suite.** Opening + Σ movements = closing, for every period and
currency. A statement over a **closed** period is byte-identical when
re-requested after further postings have landed elsewhere — the immutability
that period close buys. A statement over the **open** period is labelled
interim. A backdated posting arriving after its period closed appears on the
*current* statement carrying its earlier `valueDate` and its actual
`bookedAt`, and does not alter the already-issued statement. Both dates are
present on every line, and `bookedAt >= valueDate` for backdated entries.

**Schema & migration suite.** schema-presence tests assert every
trigger, partial unique index, composite FK, CHECK, and RLS policy exists
*and fires* (a migration that silently drops the append-only trigger fails
CI, not an audit); stepwise (V1→…→Vn) and fresh-install (empty→Vn)
migrations converge to byte-identical schemas; expand→migrate→contract
rehearsed on a copy for any change touching hot tables.

**Concurrency suite.** N threads hammering shared accounts; plus targeted
races: double-reversal of one transaction (one winner, loser gets
`ALREADY_REVERSED` + winner id); capture racing expiry sweep (exactly one
wins; money conserved either way); duplicate posting racing (blocked-insert
arbitration); duplicate hold placement; close racing an in-flight posting.
**Single-hot-account benchmark with an explicit TPS floor** on reference
hardware — if the floor fails, fan-in sharding is applied before launch.

**Idempotency suite.** Same key + same payload replayed serially and racing;
same key + **different payload** → 409 (serial and racing); failed-then-
retried semantics (registry binds committed only); holds and account creation
idempotency; key-length and fingerprint-canonicalization cases.

**Bounds & abuse suite.** 101 entries; amount = cap, cap+1, 2^53-adjacent
values with strict-long JSON parsing; zero and negative amounts; wash
transactions; oversized keys; future value dates; backdating beyond window /
without reason / into a closed period; hold TTL absent, past, beyond max.

**Immutability suite.** Raw-SQL UPDATE/DELETE against entries **and** against
account identity columns (currency/type/tenant_id) must be rejected by
triggers — tamper-evidence must not depend on application code. Also:
`currencies.minor_unit_exponent` must be immutable once referenced — the one
value that would silently reinterpret every stored amount in every tenant
while leaving all six invariants green.

**Tenant-isolation suite.** Cross-tenant probes for every mutating endpoint:
reverse another tenant's transaction, release/consume another tenant's hold,
post to another tenant's account, read another tenant's statement — all must
fail as not-found; composite-FK violation tests at the schema level; RLS
discipline tests (no tenant context ⇒ no rows). Two cases target the places
where isolation is *not* carried by a foreign key:

- **Shared `group_ref` across tenants.** Provision `fees-pool` in two
  tenants, fund both, and assert each tenant's group balance sums only its
  own shards — the one identifier not covered by a composite FK.
- **Connection-pool tenant bleed.** Borrow a pooled connection under tenant
  A, complete the request, then serve tenant B on the same physical
  connection and assert tenant A's rows are invisible. A session-scoped
  `SET app.tenant_id` passes every single-tenant test and fails this one;
  `SET LOCAL` inside the transaction passes both. The test exists because
  the failure is silent, survives code review, and defeats RLS in exactly
  the scenario RLS is there to cover.

**Failure injection.** Kill the DB connection at each step boundary;
duplicate deliveries; relay crash between publish and mark-published
(at-least-once + consumer dedupe verified); the outbox commit-order case
(late-committing low id must still publish — watermark bugs caught by test).

**Restore drill (scheduled, not merge-gating).** Quarterly: restore from
replica, verify epoch fencing, replay Orchestration's window, prove
invariants on the restored state. RPO = 0 for acknowledged commits is a
stated guarantee — drills are how it stays true.

## CI

`.github/workflows/ci.yml` runs `./mvnw verify` on every push and PR. All
suites run against real PostgreSQL — never an in-memory substitute: the
locking, trigger, constraint, and RLS behaviour under test *is* PostgreSQL's.
