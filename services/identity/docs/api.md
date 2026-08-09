# Identity — API Surface

**Status:** DRAFT v0.2 (2026-08-09).

> **The authoritative, comprehensive surface is
> [`../src/main/resources/static/openapi.yaml`](../src/main/resources/static/openapi.yaml)** —
> an OpenAPI 3.1 contract covering the full banking-core auth API (authentication, MFA/2FA,
> email & phone verification, step-up, password reset, sessions & devices, directory admin,
> roles & permissions, policy, and the audit trail). Every operation is tagged `x-status:
> built | planned`. The running service renders it at `/docs` (Swagger UI) and serves it at
> `/openapi.yaml`. This markdown is the human-readable digest; the YAML is the spec.

**Built today (the ADR 0018 swap slice + TOTP 2FA):** login, forced/voluntary
password change, refresh rotation, logout, revoke-all, service tokens, JWKS; and
TOTP MFA — enrol, activate, `/mfa/verify` login completion, step-up, disable,
status. **Delivery-gated (planned):** email/phone verification, SMS OTP, and
self-service password reset — the flows that send a code to a person, blocked on
the messaging connector (notification delivers nowhere yet). **Planned:** the
directory admin surface (built alongside Core's admin-surface §5), roles/policy/
audit query.

The rows below are the digest of the authentication surface; the YAML is the
full set-comparison source once `ApiSurfaceCatalogTest` lands.

REST/JSON at `/v1`. Two surfaces with different callers and different rules:

- **Authentication** — the platform's only pre-identity endpoints. Reached by
  clients through the edge (`/api/identity/` → this service; the edge routes
  nothing else here). These are the service's open paths.
- **Directory** — service-facing, consumed by Core's administration surface
  (admin-surface.md §5). Denies by default via `libs/auth` like every other
  endpoint on the platform; never routed by the edge; tenants never call it.

## Authentication

| Method & path | Purpose | Answer |
|---|---|---|
| `POST /v1/auth/login` | Verify a staff credential | `200` token pair · `202 ACTION_REQUIRED` · `401 AUTH_FAILED` · `429 RATE_LIMITED` |
| `POST /v1/auth/password` | Set a new password with an action grant (forced change) or a current credential | `204` · `400 PASSWORD_POLICY` · `401 AUTH_FAILED` |
| `POST /v1/auth/refresh` | Rotate a refresh token, mint a fresh access token | `200` rotated pair · `401 TOKEN_INVALID` |
| `POST /v1/auth/logout` | Revoke the presented refresh token's family | `204` (always — revoking nothing is not an error) |
| `POST /v1/auth/sessions/revoke-all` | Revoke every family for the authenticated user | `204` |
| `POST /v1/auth/token` | Mint a service token from a client credential | `200` · `401 AUTH_FAILED` · `429 RATE_LIMITED` |
| `GET /.well-known/jwks.json` | Published verification keys, current and outgoing | `200`, cacheable |

`200` from `login` and `refresh` carries `accessToken`, `refreshToken`,
`expiresIn`. `202` carries `code: ACTION_REQUIRED` with a `reason`
(`PASSWORD_CHANGE_REQUIRED` now; `MFA_REQUIRED` reserved for phase 2) and a
single-purpose `actionToken` whose only accepted use is the named action.

## Directory (service-facing, drafted with admin-surface §5 and built with it)

| Method & path | Purpose |
|---|---|
> **Built as of 2026-08-09:** `POST /v1/directory/users`, `GET /v1/directory/users`,
> `GET /v1/directory/users/{id}`, `PUT /v1/directory/users/{id}/units`,
> `POST /v1/directory/users/{id}/reset-password`, `POST /v1/directory/users/{id}/unlock`, and the
> two catalog reads — served at `/v1/directory/permissions` and `/v1/directory/roles` rather than
> at the top level, so the edge's single allowlist covers the whole administration surface without
> a per-path exclusion to remember. Everything else in this table remains planned; role authoring
> and role *changes* wait on the maker-checker work (ADR 0017 guardrail 3).

| `POST /v1/directory/users` | Create a staff member: username, names, contact, roles, units. Temporary credential generated, returned once. Idempotency-Key required; `(tenant, username)` unique index arbitrates. |
| `GET /v1/directory/users` · `GET /v1/directory/users/{id}` | Read staff, keyset-paged; one user with roles, units, status. |
| `PUT /v1/directory/users/{id}/roles` | Replace role grants. Grants restricted to catalog permissions the *initiating administrator* holds — the grantor rule is enforced here as well as in Core. |
| `PUT /v1/directory/users/{id}/units` | Replace unit assignments — the claim half of admin-surface's two-record write. |
| `POST /v1/directory/users/{id}/deactivate` · `/reactivate` | Status changes; deactivation refuses the last administrator. |
| `POST /v1/directory/users/{id}/reset-password` | Admin-initiated: new temporary credential, forced change, all families revoked. |
| `POST /v1/directory/users/{id}/unlock` | Clear a throttling lock early. |
| `GET /v1/directory/users/{id}/sessions` · `DELETE …/sessions` | List and revoke a user's refresh families. |
| `POST /v1/roles` — role composition rows | Live in Core per admin-surface §5; the directory stores composition Core has already maker-checked. The directory refuses any permission string outside the platform catalog. |

Maker-checker lives in Core (its approval machinery, per admin-surface §5); the
directory trusts a verified service caller *plus* the forwarded initiating
identity, mirrored from the ledger's `X-Forwarded-Authorization` pattern, and
records both attributions (scaffold §6).

## Error catalog

| Code | Reasons | Notes |
|---|---|---|
| `AUTH_FAILED` | **none — deliberately** | One code, no reason, uniform shape and timing for unknown user, wrong credential, disabled, locked, unknown client. A documented deviation from the error contract's reason rule: each distinguishing reason is an oracle an attacker farms. The audit row carries the true cause; the wire never does. |
| `ACTION_REQUIRED` | `PASSWORD_CHANGE_REQUIRED` · `MFA_REQUIRED` (reserved) | `202` with a single-purpose `actionToken`. |
| `PASSWORD_POLICY` | `TOO_SHORT` · `BREACHED` · `REUSED` · `MISMATCHED_ACTION` | Policy is per-tenant; the breach check runs against a bundled offline list — no external call at login time. |
| `TOKEN_INVALID` | **none** | Expired, unknown, rotated and revoked refresh tokens are indistinguishable on the wire. Reuse of a rotated token additionally revokes the family and audits — observably identical to any other refusal. |
| `RATE_LIMITED` | — | `429` with `Retry-After`. Applied per source and per account; account-level throttling never reveals that the account exists (the same `429` shape applies to nonexistent accounts). |
| `USER_NOT_FOUND` · `ROLE_UNKNOWN` · `PERMISSION_UNKNOWN` · `LAST_ADMINISTRATOR` · `USER_EXISTS` | directory only | Aligned with admin-surface §5's refusals; Core translates where its own catalog names differ. Not-found and wrong-tenant are indistinguishable, as everywhere. |

Every rejection carries the standard shape — `code`, `reason` where allowed
above, machine-readable `details`, developer-only `message` — and the service
ships the reconciling `ErrorCodeCatalogTest` from day one.
