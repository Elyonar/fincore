# Identity — Design Changelog

Amendments to this service's design. Process and entry format:
[`docs/conventions/design-changes.md`](../../../docs/conventions/design-changes.md).
Newest entry first.

The design itself is still DRAFT ([ADR 0018](../../../docs/adr/0018-first-party-identity-service.md)),
so these entries record what the code does while it converges on AGREED rather than amendments to
a frozen contract.

---

## [0.5.0] — 2026-08-11 · MINOR

**The catalog stops advertising capability the platform does not have.** Lending was withdrawn
(ADR 0013, Core 2.0.0); identity's permission catalog follows.

- The eight `loans:*` permissions leave `PermissionCatalog` — the catalog, the admin and
  supervisor role templates, and the blurbs. A permission no endpoint checks is not a permission;
  it is a promise the platform cannot keep, and a role screen offering it invites an administrator
  to grant nothing and believe they granted something.
- The `job:loan-officer` starting template and the "Loan officer" starting job title leave the
  bootstrap manifest for the same reason. Existing tenants keep any roles they authored; templates
  only seed new ones.
- MINOR, not MAJOR: no caller could exercise these permissions — every endpoint they named was
  removed with the lending module, so no working caller must act.

## [0.4.0] — 2026-08-09 · MINOR

**An institution can describe its own staff** — job titles become a vocabulary, staff numbers are
allocated rather than typed, and the administered half of a record can be set after creation.

- **New surface:** `GET /v1/directory/job-titles`, `POST /v1/directory/job-titles`,
  `DELETE /v1/directory/job-titles/{title}`, `GET /v1/directory/staff-numbering`,
  `PUT /v1/directory/staff-numbering`, `PUT /v1/directory/users/{id}/employment`.
- **Why, plainly.** `V4` gave the staff record a `job_title` and a `staff_number`, both free text
  set once at creation. That is enough to store a value and not enough to make it mean anything:
  three administrators hiring three tellers produce "Teller", "teller" and "Cashier/Teller", and
  the identifier payroll uses is whatever somebody remembered to type. Nothing could ask how many
  tellers an institution has, and nothing could fill either field in afterwards — including for
  the seeded administrator, whose own record read blank on every screen that showed it.
- **A title is not a role, and the schema says so.** A role is what somebody may do and is checked
  on every request; a title is what they are called and is checked nowhere. They are separate
  tables, separate endpoints and separate screens, because institutions that conflate them end up
  encoding place and seniority into permission sets — `job:teller-lagos` — which is exactly the
  multiplication [ADR 0012](../../../docs/adr/0012-organizational-units.md) exists to prevent.
- **Schema `V5__job_titles.sql`:** `auth.job_titles` (case-preserving, case-insensitively unique —
  "Teller" and "teller" are one job) and `auth.staff_numbering` (prefix, zero-pad width, next
  value). Both RLS-enabled and forced. No seeding in the migration, for the reason `V3` and `V4`
  give: migrations run as the owner with no tenant context, so an `INSERT … SELECT` over tenants
  would appear to succeed and write nothing. `ManifestSeeder` converges both per tenant.
- **Numbering is a counter, not a sequence.** `UPDATE … RETURNING` under the row lock arbitrates,
  so two administrators hiring in the same second take different numbers. A PostgreSQL sequence
  would not do: sequences are not tenant-scoped, and one that silently skips on rollback while
  looking gap-free is worse than an honest counter an institution can reset. `nextValue` is
  settable because an institution migrating onto this platform arrives with numbers already
  issued; the unique index stays the arbiter, so a collision refuses the hire rather than reusing
  an identifier.
- **The vocabulary is enforced, with one deliberate exception.** A job title outside the catalogue
  is refused (`JOB_TITLE_UNKNOWN`) — otherwise the catalogue is decoration. While the catalogue is
  *empty*, free text is accepted: refusing every hire because nobody has visited a settings screen
  yet would turn an empty table into a lockout.
- **New codes:** `JOB_TITLE_EXISTS`, `JOB_TITLE_UNKNOWN`, `JOB_TITLE_IN_USE`,
  `STAFF_NUMBER_TAKEN`. New audit events: `JOB_TITLE_CREATED`, `JOB_TITLE_DELETED`,
  `NUMBERING_CHANGED`, `EMPLOYMENT_CHANGED`.
- **`PUT …/employment` stays administered.** Staff number, job title and start date are facts
  about the job. `/v1/me/profile` still refuses them, because a person editing their own job title
  is not a control.
- **Seeder:** a starting vocabulary of nine titles per tenant, the numbering row, and — for
  tenants seeded before any of this existed — the super-administrator's own title, filled only
  where still null so a deliberate change is never overwritten on the next boot.

---

## [0.3.0] — 2026-08-09 · MINOR

**The staff directory is built** — the service-facing half of Core's administration surface
(admin-surface §5).

- **New surface:** `POST /v1/directory/users`, `GET /v1/directory/users`,
  `GET /v1/directory/users/{id}`, `PUT /v1/directory/users/{id}/units`,
  `POST /v1/directory/users/{id}/reset-password`, `POST /v1/directory/users/{id}/unlock`,
  `GET /v1/directory/permissions`, `GET /v1/directory/roles`.
- **Path change from the planned spec:** the two catalog reads live under `/v1/directory/`
  rather than at `/v1/permissions` and `/v1/roles`. One prefix means the edge's allowlist covers
  the whole administration surface without a per-path exclusion anyone has to remember to add.
- **Authentication (`DirectoryAuth`):** a verified **service** credential — no tenant claim,
  `azp` on `fincore.identity.directory.trusted-callers` (default `core`) — **plus** the
  initiating administrator's own token in `X-Forwarded-Authorization`. Both are required: a
  service credential alone can read nothing here, because every directory operation is something
  a human did and an audit row that cannot name them is worth little. The ledger's posture,
  applied to identity, and enforced independently of the proxy so the boundary survives a
  misconfiguration.
- **Guardrails enforced in the directory, not only in the caller:** ADR 0017 guardrail 1 (no
  administrator grants a permission they do not themselves hold, checked against the *initiating*
  administrator's effective permissions) and guardrail 0's naming rules
  (`PermissionCatalog.roleNameViolation`, ready for the role-authoring work).
- **Schema `V3__roles.sql`:** `auth.roles` — roles become first-class rows, so a role can exist
  with no permissions yet, a template is distinguishable from the tenant's own work, and creation
  is attributed. No backfill: migrations run as the owner under FORCE row-level security, so a
  backfill would appear to run and quietly copy nothing; the seeder converges instead.
- **`job:super-admin`, and why it had to exist.** Guardrail 1 is right, and it made bootstrap
  impossible: the seeded administrator held `job:admin`, which deliberately excludes the money
  path, so they could never create the institution's first teller — an administrator who could
  grant `cash:transact` without holding it can create a user, keep their temporary credential,
  sign in as them, and transact. So the bootstrap identity is now what its name always claimed: a
  super-administrator holding the whole catalog, whose first act is usually to create narrower
  administrators. Separation of duties is preserved for every administrator after the first,
  which is where it does its work. Found by testing the create path against a running stack, not
  by reading.
- **Seeding:** all seven job templates are seeded, not only the administrator's, so an institution
  can staff itself on day one without first authoring a role — the operation that needs a second
  signature and therefore a second human. `job:teller` gains the four permissions the portal
  roadmap recorded it was missing (`tills:read`, `customers:create`, `customers:link`,
  `approvals:make`), without which it could not find the till its own deposits require. The seeder
  also converges the manifest's super-administrator onto `job:super-admin` on every boot, so a
  tenant seeded before that role existed is repaired rather than stranded.
- **Catalog:** four permissions added per admin-surface §8 — `users:read`, `users:manage`,
  `accounts:read`, `accounts:manage` — taking the vocabulary from 35 to 39, and each now carries a
  one-line description served by `GET /v1/directory/permissions`. An administrator composing a
  role is choosing from this list; a list of bare strings asks them to guess.
- **Audit:** `AuthEvents.recordAs` records what a *service* did on a *human's* behalf.
  `AuthEvents.record` attributes to the subject, which is right for authentication and wrong for
  the directory — the row is about the user being created while the actor is the administrator who
  created them. ADR 0017 guardrail 4 asks who granted what to whom, and only this overload can
  answer. New events: `USER_CREATED`, `UNITS_CHANGED`, `PASSWORD_RESET`, `UNLOCKED`.
- **Errors:** the directory answers with a code and a reason, unlike the authentication surface's
  uniform `AUTH_FAILED`. That uniformity is a control for a pre-identity surface an attacker can
  reach, not a house style: an authenticated administrator asking about their own institution's
  records is owed the fact, and hiding it only moves the question to a support queue.
  `USER_NOT_FOUND`, `USER_EXISTS`, `ROLE_UNKNOWN`, `PERMISSION_UNKNOWN`,
  `PERMISSION_NOT_HELD_BY_GRANTOR`, `LAST_ADMINISTRATOR`, `FIELD_REQUIRED`, `ROLE_NAME_INVALID`.
- **Deliberately absent:** role authoring, replacing an existing user's roles, deactivate and
  reactivate — all maker-checked (ADR 0017 guardrail 3).
- **Honest edge:** the directory's own suites are **PLANNED** in `testing.md` — grantor-rule
  refusal, service-credential-only auth, forwarded-identity attribution, cross-tenant probes. The
  path was exercised end to end against a running stack and hand-reviewed; CI is the gate.

## [0.2.0] — 2026-08-09 · MINOR

TOTP MFA, step-up, recovery codes; the `auth` schema and audit vocabulary as constants.

## [0.1.0] — 2026-08-09 · MINOR

The ADR 0018 swap slice: login, forced credential change, refresh rotation with family
revocation, logout, service tokens, JWKS with `kid` rotation, manifest seeding.
