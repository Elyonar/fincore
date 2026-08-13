# What's left to get to a client portal

**Date:** 2026-08-08 · working note, not an AGREED design

Everything below is verified against source in this session rather than inferred
from documentation. Estimates are engineer-weeks at this repo's standard —
design doc, CHANGELOG, catalog tests, deny-by-default and cross-tenant probes per
endpoint — not at prototype speed.

---

## Where we actually are

**Landed as running code: one thing.** Keycloak now persists to its own
`keycloak` database with an `auth` schema, so tenant-authored identity survives a
restart. That was a precondition for everything else, and it is done.

**Landed as agreed-shaped design, not yet code:** ADR 0015 (control plane,
Deferred), ADR 0016 (tenant manifest), ADR 0017 (tenant-defined roles),
`tenant-bootstrap.md`, and `bootstrap/tenants.json`. The `services/platform`
design set was removed with the 2026-08-11 MVP trim — the ADRs remain the
record; the docs return with the deployable, if it returns.

**Unchanged:** every backend gap the client-readiness analysis found. No API has
been added this session. That is the honest starting line.

---

## The critical path

Four things stand between today and a super-administrator who can log in and
build their institution. They are strictly ordered only at the first step;
after that they fan out.

### 1. Tenant bootstrap — ADR 0016 implementation · ~1 week

Nothing else can be demonstrated until a tenant exists and someone can sign in.

- Mark ADR 0016 Accepted and `tenant-bootstrap.md` AGREED (review, no code)
- `TenantSeeder` in each of ledger, core, notification — generalize the existing
  `DevTenantSeeder` from one hardcoded UUID to the manifest list
- Manifest validation that refuses to start on a malformed entry
- `bootstrap/render-realms.sh` — realm template per entry, with the
  super-administrator and any seeded staff
- Mount the manifest into all three services in compose
- Delete `scripts/provision-tenant.sh`
- One CHANGELOG entry per service (MINOR)

**Done means:** a fresh deployment reads the array, three registries agree, a
realm exists, and a named human signs in and is forced to change their password.

### 2. The three blockers · ~5–7 weeks, parallelizable

A seeded administrator today can create organizational units, tills, approval
tiers and customers — and cannot make the institution able to transact. Three
gaps, each verified in source.

| # | Gap | Evidence | Effort |
|---|---|---|---|
| A | **Product pricing cannot be authored.** `POST /v1/products` accepts `(code, name, type)`. No endpoint writes `fee_rules` or `limit_rules`; none reads them back; `create()` hardcodes `version=1` so a product can only ever have one version; `effective_from` is unsettable. Nothing prices, so no deposit, withdrawal or transfer resolves a product version. | `ProductController.java:73`, `ProductRecords.java:53-59`; zero non-test writers of the rule tables | 2.5–3.5 wk |
| B | **No ledger account can be opened.** `POST /v1/customers/{id}/accounts` links an id the caller must already hold; `LedgerClient` has `post`/`reverse`/`read`/`get` and no open operation; the edge deliberately does not route to the ledger. Blocks customer accounts, the fee-income, funding and penalty-income accounts every configured product needs, and the account a till *is*. | `CustomerController.java:133-143`, `LedgerClient.java` | 1–1.5 wk |
| C | **No user or role administration** — ADR 0017. No endpoint creates a staff user; the seven `job:*` composites are identical for every tenant. ~~Includes the live `units` derivation gap~~ — **closed**: assigning a principal to a unit now moves the claim as well as the row, through the `UnitClaims` port Organization declares and Admin implements against the directory. Both stores move together or the write fails. | no user endpoint anywhere | 2–3 wk |

**All three change Core's `api.md`**, so `design-changes.md` rule 1 wants the
amendment in its own PR before implementation.

### 3. Portal-quality fixes · ~1–1.5 weeks

Small, independent, and each one is a screen the portal cannot finish without a
workaround.

- **No framework-level exception handlers in Core.** Malformed JSON, a missing
  `?from=`, or a null `idempotencyKey` bypass the error contract entirely and
  return Spring's default body with no `code`. A null `idempotencyKey` hits a
  `NOT NULL` constraint and surfaces as a 500 — which under Core's own retry rule
  instructs the client to retry the same key forever.
- **Statement proxy drops `limit` and `after`**, capping statements at the
  ledger's 500-line default and returning a `nextCursor` the client cannot use.
- **Money-path responses carry no timestamp and no balance-after** — both are on
  every receipt a bank prints.
- **The approvals queue has no currency, customer or description**, so a checker
  must fetch each transaction to know what they are approving.
- **`job:teller` cannot do the teller job** — missing `tills:read` (so it cannot
  find the till its own deposits require), `customers:create`/`customers:link`,
  and `approvals:make`. Config-only fix.
- **Customer search cannot find by phone or account number**, which is how a
  walk-in is identified.

### 4. The portal itself

Next.js, one app, role-gated navigation — not four SPAs. The realm's job
composites already describe the navigation trees. Order: setup (products, org
units, accounts, staff) → teller → supervisor/ops.

**It can start before the backend is finished.** Once the api.md amendment in
step 2 is agreed, the contract is stable enough to build against mocks. That is
the main parallelisation win available here and the reason the design PR is worth
doing before the implementation.

---

## Independent of the critical path

Real work, none of it blocking the portal.

- **`restore-drill.sh` covers only the ledger** (`DB=ledger`, hardcoded). The
  Keycloak database now holds state that exists nowhere else and is not in any
  backup drill.
- **The 35 permissions are string literals** with no constant and no catalog
  test — a hard-rule-10 violation, and the thing ADR 0017's guardrail 0 needs to
  check proposed role names against. Code and realm agree exactly today; nothing
  keeps them that way.
- **No real integration lane in CI.** `ui-runway.md` §2 claims a compose-profile
  Keycloak lane driving a money path; what exists is
  `JwtEndToEndTest`, an in-JVM JWKS stub with a stubbed ledger, no Keycloak, no
  realm template, no edge. Every provisioning defect found this session is one CI
  is structurally incapable of catching.
- **Edge hardening** — origin-reflecting CORS with no `Vary`, no `Max-Age`,
  methods limited to GET/POST/OPTIONS, `listen 80` with no TLS config, no rate
  limits.

---

## Recommended next move

**One design amendment PR against Core covering all three blockers at once** —
product rule authoring, account opening, and user/role administration — as
`api.md` rows plus a CHANGELOG entry and a version bump (core v1.19 → v1.20,
MINOR).

One PR rather than three, because they share error codes, permission checks and
pagination conventions, and because it unblocks two workstreams simultaneously:
backend implementation can fan out across A, B and C, and portal development can
begin against an agreed contract instead of waiting.
