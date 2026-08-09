# Platform — The Provisioning Protocol

**Status:** DRAFT v1.0 (2026-08-08) — amendments via [`CHANGELOG.md`](CHANGELOG.md)

How a tenant comes into existence across an identity provider and three
deployables without ever half-existing. This is a saga, and it borrows Core's
[`outcome-protocol.md`](../../core/docs/outcome-protocol.md) rather than
paraphrasing it: the classification of an answer into *definitely succeeded*,
*definitely refused*, and *unknown* is the same problem here as on the money
path, and a second implementation of that judgement would eventually disagree
with the first.

---

## 1. Why a saga and not a transaction

There is no transaction that spans Keycloak and three PostgreSQL databases owned
by three deployables, and there should not be. What is available is: an operation
that can be retried under the same key without doubling, a record of what each
participant said, an undo for each step, and a worker that finishes what a crash
interrupted. That is a saga, and this codebase already contains the best example
of one it will find.

The failure this exists to prevent has a name and a shipped instance:
`scripts/provision-tenant.sh` creates a realm, inserts one registry row, and — if
that insert fails — deletes the realm. It has a compensation. What it does not
have is the other two participants, or any answer for the case where the insert
neither succeeds nor fails but times out. Under the current script that tenant is
left with a deleted realm and, possibly, a committed registry row.

## 2. The participants, in order

Order is not arbitrary. Cheapest-to-undo first, so a late refusal compensates
little; the irreversible-in-practice step last, so it is attempted only once
everything else has already succeeded.

| # | Participant | Operation | Compensation |
|---|---|---|---|
| 1 | `realm` | Create the realm from the versioned template, rendered with this tenant's identifiers and origin | Delete the realm |
| 2 | `ledger` | Register the tenant | Deregister |
| 3 | `core` | Register the tenant | Deregister |
| 4 | `notification` | Register the tenant | Deregister |
| 5 | `administrator` | Create the institution's first administrator in the realm, with the tenant's admin job composite and an initial-credential requirement | Delete the user |

The realm comes first because it is the only participant whose failure is likely
for a benign reason — a name already taken — and discovering that after three
registry writes wastes three compensations.

The administrator comes last because a realm with no users is the current defect
in the shipped script: provisioning reports success and nobody can log in. **A
run is not complete until a human can sign in.** That is the definition of done
this protocol enforces, and it is why step 5 is inside the saga rather than a
follow-up task.

## 3. What a participant must implement

One endpoint per participant, on its own `/internal/v1` surface.

```
POST   /internal/v1/tenants           { tenantId, name, idempotencyKey }
DELETE /internal/v1/tenants/{id}      (compensation; only ever for a tenant this protocol created)
```

Four properties, each of which is a test the participant owns:

1. **Verified service caller only.** The call carries a peer service identity and
   is refused unless it is on the participant's allowlist — the mechanism
   `Authorization.requireCallerAnyOf` already provides and the ledger already
   uses. A tenant's user token must be refused here in every case, in every
   state, forever. This is what preserves the existing invariant that no request
   path can enrol its own caller's tenant.
2. **No tenant gate on this path.** The endpoint's entire purpose is that the
   tenant does not exist yet. A participant whose `TenantGate` runs before it
   answers 404 to the operation that would have made the answer 200.
3. **Idempotent.** Same key, same payload replays and returns the same answer.
   The registries already have `ON CONFLICT (id) DO NOTHING`; this makes that
   behaviour a contract instead of an implementation detail.
4. **Compensation is safe on a tenant that was never registered**, because
   compensation runs after an unknown outcome is finally classified as a refusal,
   and the participant may or may not have done the work.

Participants are configuration, not code: the list is a property, and a fifth
deployable joins by implementing the contract and being named.

## 4. Running

```
  claim ─▶ for each participant in order:
              ask
              ├── SUCCEEDED    → record, continue
              ├── REFUSED      → record, COMPENSATE, run = COMPENSATED
              └── UNKNOWN      → record, STOP, run = INDETERMINATE
           all succeeded → run = SUCCEEDED, tenant → PROVISIONED
```

Three rules, and every one of them is a rule because its opposite is a plausible
mistake.

**A refusal compensates. An unknown never does.** Compensating an operation that
may have succeeded is how a live tenant loses its realm. `REFUSED` is a
participant's definite answer, carrying its own error code; `UNKNOWN` is silence,
and silence is not a refusal. Recorded as three values in
`provisioning_steps.outcome` precisely so no code path can collapse them into a
boolean.

**An unknown stops the run where it stands.** It does not skip ahead and it does
not roll back. The run sits in `INDETERMINATE`, holding everything it has already
built, until convergence resolves the one step whose answer is missing.

**Compensation walks the recorded steps in reverse and only the succeeded ones.**
It reads `provisioning_steps`, not a variable in memory, so a worker that dies
mid-compensation resumes correctly rather than starting over.

## 5. Converging

A worker claims `INDETERMINATE` runs by lease — the pattern Core's retry worker
already uses, so a dead worker's run is reclaimed rather than stranded — and
re-drives the outstanding step **under the same idempotency key**. Re-driving is
what turns an unknown into a definite answer: a participant that already did the
work replays and says so; one that never did, does it now.

A run that cannot be resolved after the configured attempt budget is escalated,
not decided. It stays `INDETERMINATE`, the tenant stays out of `PROVISIONED`, and
an operator is shown a run that needs a human. Guessing at the end of a retry
budget would defeat the entire protocol at exactly the moment it matters.

## 6. Idempotency

The console supplies a key per provisioning attempt and reuses it across every
retry of that attempt. A unique index on `provisioning_runs.idempotency_key`
arbitrates the race — application code does not, per the scaffold.

- Same key, same payload → the existing run is returned. A double-clicked button
  produces one tenant.
- Same key, different payload → `IDEMPOTENCY_CONFLICT`, loudly. Never a silent
  wrong answer.
- A new key while a run is in flight for that tenant → `RUN_IN_FLIGHT`. The
  partial unique index makes concurrent runs on one tenant unrepresentable, so
  this is a refusal and not a queue.

## 7. What the console sees

`POST /v1/tenants/{id}/provisioning-runs` returns `202` immediately with the run.
The console then polls `GET /v1/provisioning-runs/{runId}` and renders the
per-participant steps as they settle.

The interface obligation this creates is the reason it is stated here and not
only in [`console.md`](console.md): **`INDETERMINATE` must be rendered as its own
state.** Not a spinner that implies progress, and not an error. The correct words
are close to *"we do not yet know whether this step completed; we are finding
out"*, and the correct affordance is neither a retry button nor a cancel button,
because the protocol is already handling it and a second run is refused anyway.

An interface that shows two states for a three-state protocol will eventually
tell an operator a tenant failed when it did not, and the operator will do
something about it.
