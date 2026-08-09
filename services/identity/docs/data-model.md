# Identity — Data Model

**Status:** DRAFT v0.1 (2026-08-09)

One database (`identity`), one schema (`identity`), two database roles per
scaffold §4: migrations as the schema owner, traffic as a restricted role —
`NOSUPERUSER`, `NOBYPASSRLS`, DML only. Every tenant-scoped table carries
`tenant_id`, RLS enabled **and FORCEd**, `SET LOCAL` context inside the request
transaction. Plain SQL over JDBC (design D10).

## Tables

| Table | Purpose · the columns that carry the design |
|---|---|
| `tenants` | The service's tenant registry, seeded from the manifest like the other three. `id`, `name`. |
| `users` | Staff. `id`, `tenant_id`, `username` (unique per tenant by index — the idempotency arbiter for creation), `email`, `first_name`, `last_name`, `status` (`ACTIVE` / `DISABLED`), `credential_temporary` boolean, `created_by` + `created_via` (dual attribution). |
| `credentials` | One current row per user, separated from `users` so directory reads never touch hash material. `user_id`, `password_hash` (Argon2id, parameters embedded in the encoded form), `updated_at`, `history` of prior digests for the reuse check — bounded, oldest dropped. |
| `user_roles` | `user_id` → role name. Role *composition* is Core's record (admin-surface §5); this table stores grants so the permissions claim can be resolved at mint time. |
| `role_permissions` | The tenant's composed roles flattened to catalog permissions, written by the directory API after Core's maker-check. Every permission string is validated against the platform catalog on write — an unknown string is refused, never stored. |
| `user_units` | `user_id` → unit code: the claim half of the two-record unit assignment. |
| `refresh_families` | One row per login: `id`, `tenant_id`, `user_id`, `client_id`, `created_at`, `absolute_expiry`, `revoked_at`, `revoked_reason` (`LOGOUT` / `ROTATION_REUSE` / `ADMIN` / `PASSWORD_CHANGE`). |
| `refresh_tokens` | `family_id`, `token_digest` (unique), `issued_at`, `rotated_at`. The presented value is digested and looked up; a match on a **rotated** row is the theft signal that revokes the family. Plaintext is never stored. |
| `login_throttle` | Per `(tenant_id, username)` and per source: `failure_count`, `window_start`, `locked_until`. Rows, not memory, so instances agree (D16). Also rowed for usernames that do not exist — the throttle must not be an existence oracle. |
| `service_clients` | `client_id`, `secret_digest`, `enabled`. The credential behind `POST /v1/auth/token`; seeded from deployment configuration by reference, never a literal. |
| `auth_events` | Append-only audit (D11): `tenant_id`, `user_id` nullable, `event` (LOGIN_OK, LOGIN_FAILED, LOCKED, UNLOCKED, ROTATED, REUSE_REVOKED, PASSWORD_CHANGED, USER_CREATED, ROLES_CHANGED, …), `actor_principal` + `actor_service` (dual attribution), `source`, `details` jsonb, `at`. Insert-only enforced by trigger, like the ledger's append-only rule. |
| `signing_keys` | **Deliberately absent.** Keys are deployment-supplied by reference (D13). The table that would make key custody a database-compromise problem is the one this design refuses to create. |

## Constraints that carry security

- `(tenant_id, id)` composite FKs everywhere a tenant-scoped row references
  another, so a cross-tenant reference cannot be expressed (scaffold §5).
- `refresh_tokens.token_digest` unique — replay arbitration is an index, not
  application code, the same rule idempotency follows.
- `credentials` readable only by the traffic role's narrowest grant; no view
  or query path joins hash material into directory reads.
- `auth_events` insert-only by trigger; UPDATE and DELETE are refused at the
  schema, asserted by the schema-enforcement suite.
- The last-administrator refusal is enforced in a serialized transaction over
  `user_roles`, and its race (two concurrent removals) has a dedicated test.

## Retention

`refresh_tokens` rows purge after family expiry + a forensic window;
`login_throttle` rows expire with their window; `auth_events` retains per the
platform's audit posture (append-only, exported before any purge — the
tamper-evident audit chain remains a platform-level PRD §4.11 concern this
service feeds, not solves).
