# Platform — Data Model

**Status:** DRAFT v1.0 (2026-08-08) — amendments via [`CHANGELOG.md`](CHANGELOG.md)

Its own database, per hard rule 5. Migrations run as the schema owner; traffic
connects as `platform_app` (`NOSUPERUSER`, `NOBYPASSRLS`, DML only), per the
scaffold.

**No row-level security anywhere in this service, and that is a decision.** RLS
scopes rows to a tenant. These tables are *about* tenants and are read by callers
who have none; a policy here would either be inert or would hide the control
plane's own records from the control plane. The isolation that matters is the
process boundary and the operator permission vocabulary, both of which are
stated. Written down because an unstated omission of an ADR 0007 control reads as
an oversight.

---

## 1. `tenants` — the institutions

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | The tenant id every other service knows. Chosen here, never elsewhere. |
| `realm` | TEXT NOT NULL UNIQUE | The identity realm name. Immutable once set. |
| `legal_name` | TEXT NOT NULL | The licensed entity. ADR 0012: tenant ≙ legal entity. |
| `display_name` | TEXT NOT NULL | What the institution calls itself. |
| `country_code` | TEXT NOT NULL | ISO 3166-1 alpha-2. Selects the country pack (PRD §3.1); no behaviour hangs off it yet. |
| `segment` | TEXT NOT NULL | `MFB` \| `COOPERATIVE` \| `PSB` \| `FINTECH` \| `OTHER`. Reporting only. |
| `status` | TEXT NOT NULL | The lifecycle state. CHECK-constrained to the nine states in `design.md` §4. |
| `web_origin` | TEXT NOT NULL | The institution's client origin. Drives the realm's redirect and origin configuration. |
| `client_secret_fingerprint` | TEXT | A digest of the current service client secret — enough to answer "is this the one I was given?", never enough to reconstruct it. Null before provisioning. |
| `created_at` / `created_by` | TIMESTAMPTZ / TEXT | Dual attribution, per the scaffold. |
| `executed_by` | TEXT | The service identity that acted. Two columns, two questions. |

Constraints that carry meaning:

- `realm` is **UNIQUE and immutable** — enforced by a trigger refusing any UPDATE
  that changes it. A realm rename orphans every token ever issued.
- `status` transitions are enforced by a trigger against the state machine, not
  by application code. An UPDATE that would move `CLOSED → LIVE` is rejected at
  the database, which is where the platform puts its irreversibility guarantees.
- `web_origin` is mutable; changing it is an operation, not an UPDATE, because it
  must be pushed to the realm to take effect.

## 2. `provisioning_runs` — the saga

One row per attempt. This is the table that makes a half-provisioned tenant
impossible to reach without leaving evidence.

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `tenant_id` | UUID NOT NULL FK → `tenants(id)` | |
| `idempotency_key` | TEXT NOT NULL | Caller-supplied, per the scaffold. |
| `payload_fingerprint` | TEXT NOT NULL | Same key + same payload replays; same key + different payload is a loud 409. |
| `state` | TEXT NOT NULL | `RUNNING` \| `SUCCEEDED` \| `COMPENSATED` \| `INDETERMINATE`. A run's states are not the tenant's: a `COMPENSATED` run leaves its tenant in `FAILED`, and an `INDETERMINATE` run leaves the tenant in `INDETERMINATE` too. Kept as separate vocabularies because a tenant outlives its runs. |
| `decision` | JSONB | The full snapshot the run was decided on — realm name, participant list, template version, requested administrator. An examiner reconstructs any past run from this column alone. |
| `failure_reason` | TEXT | A documented code, never prose. Null unless `COMPENSATED`. |
| `attempts` | INT NOT NULL DEFAULT 0 | |
| `lease_until` | TIMESTAMPTZ | Claimed by the convergence worker; a dead worker's run is reclaimed rather than stranded. |
| `started_at` / `settled_at` | TIMESTAMPTZ | |
| `initiated_by` / `executed_by` | TEXT | |

- `UNIQUE (idempotency_key)` — **a unique index arbitrates the idempotency race,
  not application code**, per the scaffold. Two consoles clicking Provision twice
  produce one run.
- Partial unique index: at most one `RUNNING` or `INDETERMINATE` run per tenant.
  Concurrent attempts on one tenant are a defect, not a queue.

## 3. `provisioning_steps` — what each participant said

Append-only. One row per participant per run, and the reason compensation knows
what to undo.

| Column | Type | Notes |
|---|---|---|
| `run_id` | UUID NOT NULL FK | |
| `participant` | TEXT NOT NULL | `realm` \| `ledger` \| `core` \| `notification` \| `administrator`. |
| `sequence` | INT NOT NULL | Execution order; compensation walks it in reverse. |
| `outcome` | TEXT NOT NULL | `SUCCEEDED` \| `REFUSED` \| `UNKNOWN` — the three-valued protocol, stored as three values. Nothing collapses `REFUSED` and `UNKNOWN` into "failed"; the whole design depends on the difference. |
| `detail` | JSONB | The participant's answer, including its error code where it refused. |
| `compensated_at` | TIMESTAMPTZ | Null unless undone. |
| `at` | TIMESTAMPTZ NOT NULL | |

- `UNIQUE (run_id, participant)` — a participant is asked once per run; retries
  re-drive the same key rather than adding rows.
- An append-only trigger refuses UPDATE and DELETE except for setting
  `compensated_at` exactly once. The audit value of this table is entirely in it
  being unchangeable.

## 4. `readiness_items` — the CONFIGURING checklist

What must be true before a tenant may take its first transaction. Seeded per
tenant when a run succeeds, from a versioned catalog.

| Column | Type | Notes |
|---|---|---|
| `tenant_id` | UUID NOT NULL FK | |
| `item` | TEXT NOT NULL | `ORG_UNITS` \| `STAFF_ROLES` \| `INTERNAL_ACCOUNTS` \| `PRODUCTS_PUBLISHED` \| `TILLS` \| `APPROVAL_TIERS` \| `NOTIFICATION_TEMPLATES`. |
| `required` | BOOLEAN NOT NULL | Some items are advisory per segment — a solo lender has no tills. |
| `completed_at` / `completed_by` | TIMESTAMPTZ / TEXT | |

`UNIQUE (tenant_id, item)`. `CONFIGURING → LIVE` is refused by the state trigger
while any `required` item is incomplete.

**These are assertions, not verifications.** This service records that the
institution's administrator completed a step; it does not read Core to confirm
it, because §2 of `design.md` forbids that. The honest limitation is stated in
`design.md` §7 and repeated here so a reader of the schema alone reaches it too.

## 5. `operator_actions` — the audit trail

Every state-changing operation on this service, append-only: what, on which
tenant, by whom, with which second signature where one was required.

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `tenant_id` | UUID FK | Null for actions with no subject. |
| `action` | TEXT NOT NULL | |
| `made_by` / `checked_by` | TEXT / TEXT | Null `checked_by` for single-actor actions. |
| `detail` | JSONB | |
| `at` | TIMESTAMPTZ NOT NULL | |

A CHECK refuses `checked_by = made_by`, and refuses a null `checked_by` on the
maker-checked actions (`SUSPEND`, `RESUME`, `CLOSE`). The same schema-enforced
maker-checker the orchestration module already uses for reversals; the control
plane does not get a weaker version of a control the money path already has.

## 6. Deliberately absent

- **No customer, account, product, or monetary column of any kind.** If one is
  ever proposed here, the proposal is in the wrong service.
- **No stored client secret** — decision 13. A fingerprint and nothing more.
- **No cached copy of a participant's registry.** Whether the ledger knows a
  tenant is the ledger's answer to give; a mirror here would go stale and be
  believed.
- **No tenant configuration.** Timezones, currencies, limits and policies belong
  to the services that enforce them.
- **No RLS**, per the note at the top — the one scaffold control this service
  states rather than implements.
