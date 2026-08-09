# Platform — Testing

**Status:** DRAFT v1.0 (2026-08-08) — amendments via [`CHANGELOG.md`](CHANGELOG.md)

Every suite carries a marker. Only **IMPLEMENTED** suites gate merges, and moving
a marker requires the tests to exist — the scaffold's rule, and the reason a
design that describes verification which does not run is worse than an admitted
gap.

All markers below are **PLANNED** because no code exists yet. This file is the
implementation PR's checklist.

Real PostgreSQL throughout, never an in-memory substitute: the triggers,
constraints and unique-index races under test *are* PostgreSQL's.

---

## 1. Schema presence — PLANNED

Every constraint this design leans on exists and fires: the state-transition
trigger, the realm-immutability trigger, the append-only triggers on
`provisioning_steps` and `operator_actions`, the unique index on
`idempotency_key`, the partial unique index on in-flight runs per tenant, the
maker ≠ checker CHECK.

A migration that silently loses one of these must fail CI, not an audit.

## 2. Schema enforcement — PLANNED

Raw SQL attempts each violation and is rejected, with no application code in the
path. Tamper-evidence that depends on the service being correct is not
tamper-evidence.

- `CLOSED → LIVE`, and every other transition the state machine forbids
- Renaming a realm
- Updating or deleting a recorded provisioning step, or setting `compensated_at`
  twice
- Two `RUNNING` runs for one tenant
- An `operator_actions` row where `checked_by = made_by`
- A maker-checked action with a null `checked_by`

## 3. The provisioning saga — PLANNED

The suite that justifies the design. Each scenario is named after the guarantee
it proves.

- **A refusal at each participant, one test per position**, asserting that every
  earlier participant was compensated in reverse order and no later one was
  called.
- **An unknown at each position** asserting the opposite: nothing compensated,
  the run `INDETERMINATE`, the tenant not `PROVISIONED`, and the outstanding step
  recorded as `UNKNOWN` rather than as a failure.
- **Convergence resolves an unknown** by re-driving the same idempotency key, in
  both directions — the participant that had already done the work, and the one
  that had not.
- **Convergence exhausts its budget and escalates**, leaving the run
  `INDETERMINATE`. The test asserts that no state was guessed at the end of the
  retry budget, which is the moment the protocol is most tempting to abandon.
- **Compensation resumes after a crash**, reading recorded steps rather than
  memory.
- **A worker dies mid-lease and another reclaims the run.**
- **A run is not `SUCCEEDED` until an administrator exists** — the definition of
  done, asserted rather than assumed.

## 4. Idempotency and concurrency — PLANNED

- Same key, same payload replays and yields one tenant
- Same key, different payload is a loud `IDEMPOTENCY_CONFLICT`
- Two concurrent provisioning requests for one tenant: exactly one run, the
  other refused `RUN_IN_FLIGHT`, arbitrated by the index and not by application
  code
- The double-clicked button, driven concurrently, produces one realm

## 5. Authorization — PLANNED

The suite that keeps the control-plane boundary real.

- **Deny-by-default probe on every endpoint.** A token with no permissions is
  refused everywhere. Enumerated from the route list so a new endpoint without a
  probe fails the suite.
- **A tenant-realm token is refused on every endpoint**, in every state. This is
  the boundary ADR 0015 exists to draw and it deserves an explicit test rather
  than an inference from the realm topology.
- **A platform token is refused by Core, Notification and the ledger** — the
  mirror-image assertion, and the one that proves an operator credential cannot
  reach tenant data even if it leaks. It lives in this suite because this is the
  service whose tokens they are.
- **Maker-checker**: same principal refused as checker; an approval is single-use;
  an approval bound to one tenant cannot execute against another.

## 6. Participant contract — PLANNED

Owned here, run against each participant, so the contract in
`provisioning-protocol.md` §3 is verified rather than described.

- A tenant user token is refused on `/internal/v1/tenants`, always
- An unlisted service caller is refused
- No tenant gate runs on the path — the endpoint answers for a tenant that does
  not exist yet
- Registration is idempotent under replay
- Compensation is safe on a tenant that was never registered

## 7. End-to-end, against a real identity provider — PLANNED

A compose-profile lane: a throwaway realm, a real provisioning run through all
five participants, an administrator who then obtains a token and reaches Core.

**This lane is the first thing in this repository that would have caught the
single-registry defect** in `scripts/provision-tenant.sh`. That defect survived
because every existing test seeds its tenant directly through `TenantRegistry`,
so no test ever exercised the provisioning path a human uses. Recording the
reason here so the lane is understood as load-bearing rather than as slow CI.

## 8. ArchUnit — PLANNED

- No dependency on any tenant-scoped service's code, in either direction
- No money type, no `java.util.Date`, per the platform-wide rules
- No JDBC template anywhere but this service's own datasource — the "never writes
  another service's database" boundary as a build failure rather than a promise
- **An empty-import canary**, per the scaffold: every `no…should…` rule passes
  vacuously when nothing was imported, and a toolchain bump can silently make an
  entire suite enforce nothing.

## 9. Deliberately not tested here

- The identity provider's own behaviour. We test that we asked correctly and
  handled all three answers, not that realm creation works.
- Anything about a tenant's business data, because this service has no path to
  it. The absence of that path is tested in §5; its behaviour is not ours.
