# Identity — Design Changelog

Amendments to this service's design. Process and entry format:
[`docs/conventions/design-changes.md`](../../../docs/conventions/design-changes.md).
Newest entry first.

The design itself is still DRAFT ([ADR 0018](../../../docs/adr/0018-first-party-identity-service.md)),
so these entries record what the code does while it converges on AGREED rather than amendments to
a frozen contract.

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
