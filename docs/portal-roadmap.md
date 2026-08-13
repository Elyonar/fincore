# What's left to get to a client portal

**Date:** 2026-08-08 · working note, not an AGREED design ·
**superseded by delivery, 2026-08-13**

> **Read this first.** The critical path below is **delivered**. Tenant
> bootstrap, product authoring, account opening, and user and role
> administration are all built and running, and the portal exists. The note is
> kept because the *analysis* is still worth reading — it is a record of what was
> actually broken and what each gap cost — but nothing in §1–§3 is outstanding
> work, and the effort estimates are history.
>
> What remains genuinely open is in **"Independent of the critical path"** and in
> each service's own known-limitations section, which are maintained.

Everything below was verified against source at the time of writing rather than
inferred from documentation. Estimates are engineer-weeks at this repo's
standard — design doc, CHANGELOG, catalog tests, deny-by-default and
cross-tenant probes per endpoint — not at prototype speed.

---

## Where we actually are

*As at 2026-08-08, when this note was written.* Keycloak persisted to its own
database so tenant-authored identity survived a restart; ADR 0015 (control
plane, Deferred), ADR 0016 (tenant manifest) and ADR 0017 (tenant-defined roles)
existed as agreed-shaped design and no code; and every backend gap the
client-readiness analysis found was unchanged. That was the honest starting
line.

**As at 2026-08-13:** Keycloak is retired entirely
([ADR 0018](adr/0018-first-party-identity-service.md)); ADR 0016, 0017, 0018,
0019 and 0020 are Accepted and implemented; Customer and Product are deployables
of their own; and all three blockers below are closed. The `services/platform`
design set was removed with the 2026-08-11 MVP trim — the ADRs remain the record;
the docs return with the deployable, if it returns.

---

## The critical path

Four things stand between today and a super-administrator who can log in and
build their institution. They are strictly ordered only at the first step;
after that they fan out.

### 1. Tenant bootstrap — ADR 0016 implementation · ~1 week · **DONE**

Nothing else can be demonstrated until a tenant exists and someone can sign in.
Delivered, though not as written: the realm half was overtaken by ADR 0018, so
the identity service's `ManifestSeeder` converges to the manifest on every boot
instead, and `bootstrap/seed-registries.sh` registers the tenant with the five
deployables that gate on a registry.

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

### 2. The three blockers · ~5–7 weeks, parallelizable · **ALL THREE CLOSED**

A seeded administrator could create organizational units, tills, approval tiers
and customers — and could not make the institution able to transact. Three gaps,
each verified in source at the time, and each now built. The evidence columns are
left as they were: they are the record of what was actually wrong.

| # | Gap | Evidence | Effort |
|---|---|---|---|
| A | ~~**Product pricing cannot be authored.**~~ **Built.** `POST /v1/products` accepts `(code, name, type)`. No endpoint writes `fee_rules` or `limit_rules`; none reads them back; `create()` hardcodes `version=1` so a product can only ever have one version; `effective_from` is unsettable. Nothing prices, so no deposit, withdrawal or transfer resolves a product version. | `ProductController.java:73`, `ProductRecords.java:53-59`; zero non-test writers of the rule tables | 2.5–3.5 wk |
| B | ~~**No ledger account can be opened.**~~ **Built** — `POST /v1/customers/{customerId}/accounts/open`. `POST /v1/customers/{id}/accounts` links an id the caller must already hold; `LedgerClient` has `post`/`reverse`/`read`/`get` and no open operation; the edge deliberately does not route to the ledger. Blocks customer accounts, the fee-income, funding and penalty-income accounts every configured product needs, and the account a till *is*. | `CustomerController.java:133-143`, `LedgerClient.java` | 1–1.5 wk |
| C | ~~**No user or role administration**~~ **Built** — ADR 0017. No endpoint creates a staff user; the seven `job:*` composites are identical for every tenant. ~~Includes the live `units` derivation gap~~ — **closed**: assigning a principal to a unit now moves the claim as well as the row, through the `UnitClaims` port Organization declares and Admin implements against the directory. Both stores move together or the write fails. | no user endpoint anywhere | 2–3 wk |

**All three changed Core's `api.md`**, and each landed with its amendment.

### 3. Portal-quality fixes · ~1–1.5 weeks · **DONE**

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

### 4. The portal itself · **BUILT**

Next.js, one app, role-gated navigation — not four SPAs, which is what shipped.
The `job:*` composites describe the navigation trees. Order as planned: setup
(branches, staff, accounts, product, pricing, publish, till) → teller →
supervisor/ops. Setup is a sequence of its own that gates the dashboard, so a
new institution is set up rather than dropped into an empty screen.

---

## Independent of the critical path

Real work, none of it blocking the portal.

- **`restore-drill.sh` covers only the ledger** (`DB=ledger`, hardcoded). Five
  other databases now hold state that exists nowhere else — `identity` most
  sharply, since it holds staff credentials and roles — and none is in any backup
  drill.
- **Permissions are string literals at each call site.** The catalog itself is
  now code — `PermissionCatalog` in the identity service, which ADR 0017's
  guardrail 0 checks proposed role names against — but nothing fails the build
  when a controller requires a permission the catalog does not contain.
- **No full-stack integration lane in CI.** CI runs every service's suite against
  real PostgreSQL and the Core↔Ledger contract suite against a real Ledger, which
  is more than this note found. What it still does not do is stand the whole
  stack up behind the edge and drive a money path through it, so every
  provisioning and routing defect remains one CI is structurally incapable of
  catching.
- **Edge hardening** — origin-reflecting CORS with no `Vary`, no `Max-Age`,
  methods limited to GET/POST/OPTIONS, `listen 80` with no TLS config, no rate
  limits.

---

## Recommended next move

*Superseded — the amendment this section asked for was made, and all three
blockers shipped. What it recommended is left below as a record of the reasoning,
which held.*

**One design amendment PR against Core covering all three blockers at once** —
product rule authoring, account opening, and user/role administration — as
`api.md` rows plus a CHANGELOG entry and a version bump.

One PR rather than three, because they share error codes, permission checks and
pagination conventions, and because it unblocks two workstreams simultaneously:
backend implementation can fan out across A, B and C, and portal development can
begin against an agreed contract instead of waiting.
