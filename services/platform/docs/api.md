# Platform — API

**Status:** DRAFT v1.0 (2026-08-08) — amendments via [`CHANGELOG.md`](CHANGELOG.md)

Two surfaces with different audiences and different callers, and they are kept
apart because conflating them is how an operator endpoint becomes reachable by a
tenant.

- **`/v1/…`** — the operator surface. Consumed by the console
  ([`console.md`](console.md)). Platform-realm tokens, deny-by-default per
  endpoint.
- **`/internal/v1/…`** — nothing. This service exposes no internal surface; it is
  the *caller* of the participants' internal surfaces, specified in
  [`provisioning-protocol.md`](provisioning-protocol.md) §3.

Conventions inherited unchanged from the platform: deny-by-default permission per
endpoint; caller-supplied idempotency key on every creating operation with a
payload fingerprint; keyset pagination with an opaque `page` cursor, never an
offset; timestamps ISO-8601 UTC; errors per
[`error-contract.md`](../../../docs/conventions/error-contract.md) — `code`,
`reason` where one code spans several causes, machine-readable `details`, and a
`message` that is developer English nobody parses.

There is no money in this service, so the decimal-string rule has nothing to
apply to. Stated rather than omitted.

---

## 1. Endpoints

| Method | Path | Permission | Purpose |
|---|---|---|---|
| `POST` | `/v1/tenants` | `platform:tenants:create` | Create a tenant in `DRAFT`. No external effect. |
| `GET` | `/v1/tenants` | `platform:tenants:read` | List, filtered by `status=` and `q=`, keyset-paged. |
| `GET` | `/v1/tenants/{id}` | `platform:tenants:read` | One tenant, with its readiness checklist and latest run. |
| `PATCH` | `/v1/tenants/{id}` | `platform:tenants:create` | Edit a `DRAFT`. Refused in every other state. |
| `DELETE` | `/v1/tenants/{id}` | `platform:tenants:create` | Discard a `DRAFT`. Refused in every other state — this is not offboarding. |
| `POST` | `/v1/tenants/{id}/provisioning-runs` | `platform:tenants:provision` | Start a run. Idempotent. Returns `202` with the run. |
| `GET` | `/v1/tenants/{id}/provisioning-runs` | `platform:tenants:read` | Every run for this tenant, newest first. |
| `GET` | `/v1/provisioning-runs/{runId}` | `platform:tenants:read` | One run with its per-participant steps. **The console polls this.** |
| `POST` | `/v1/tenants/{id}/administrators` | `platform:tenants:provision` | Seed an additional administrator into the tenant's realm. Idempotent by username. |
| `POST` | `/v1/tenants/{id}/client-secret/rotate` | `platform:secrets:rotate` | Mint a new service client secret. Returned once, never again. |
| `POST` | `/v1/tenants/{id}/web-origin` | `platform:tenants:create` | Change the client origin and push it to the realm. |
| `POST` | `/v1/tenants/{id}/readiness/{item}` | `platform:tenants:read` | Mark a checklist item complete. Assertion, not verification. |
| `POST` | `/v1/tenants/{id}/go-live` | `platform:tenants:golive` | `CONFIGURING → LIVE`. Refused while a required item is incomplete. |
| `POST` | `/v1/tenants/{id}/suspend` | `platform:tenants:suspend` | Maker-checked. Withdraws access; retains data. |
| `POST` | `/v1/tenants/{id}/resume` | `platform:tenants:suspend` | Maker-checked. |
| `POST` | `/v1/tenants/{id}/close` | `platform:tenants:close` | Maker-checked. Only from `SUSPENDED`. Terminal. |
| `GET` | `/v1/tenants/{id}/actions` | `platform:tenants:read` | The audit trail for this tenant. |
| `GET` | `/v1/catalog/readiness-items` | `platform:tenants:read` | The checklist catalog and which items each segment requires. |

Maker-checked operations (`suspend`, `resume`, `close`) follow the platform's
existing shape rather than inventing one: a first call records the intent and
returns `202` with a pending approval; a second call by a different operator
carrying the approval id executes it. The database refuses maker == checker
regardless of what the handler does — the same arrangement the orchestration
module uses for reversals.

## 2. Permission vocabulary

Seven permissions, in the platform realm only. They exist nowhere in a tenant
realm and a tenant token can never carry them, because a tenant realm has no such
roles to grant.

| Permission | Grants |
|---|---|
| `platform:tenants:read` | Every read on this service, including the audit trail |
| `platform:tenants:create` | Draft lifecycle: create, edit, discard, web origin |
| `platform:tenants:provision` | Start runs; seed administrators |
| `platform:tenants:golive` | The `CONFIGURING → LIVE` transition |
| `platform:tenants:suspend` | Suspend and resume, as maker or checker |
| `platform:tenants:close` | Close, as maker or checker |
| `platform:secrets:rotate` | Mint a replacement service client secret |

Two composites, matching the job shapes the tenant realms already use:

- `job:platform-operator` — `read`, `create`, `provision`, `golive`
- `job:platform-admin` — the operator composite plus `suspend`, `close`,
  `secrets:rotate`

A single operator cannot both make and check a suspension, because the check is
enforced on the principal and not on the permission. Two people holding
`job:platform-admin` are required, which is the point.

## 3. Error catalog

Every code documented here exists in the enum, and every enum value is documented
here — kept honest in both directions by `ErrorCodeCatalogTest`, copied per the
scaffold.

| Code | HTTP | Reason(s) | `details` |
|---|---|---|---|
| `COMMAND_INVALID` | 422 | `MISSING_FIELD`, `MALFORMED_REALM_NAME`, `MALFORMED_ORIGIN`, `UNKNOWN_SEGMENT`, `UNKNOWN_COUNTRY` | `field` |
| `REALM_TAKEN` | 409 | — | `realm` |
| `TENANT_NOT_FOUND` | 404 | — | — |
| `ILLEGAL_TRANSITION` | 422 | `FROM_STATE`, `RUN_IN_FLIGHT`, `READINESS_INCOMPLETE` | `from`, `to`, `outstanding[]` |
| `IDEMPOTENCY_CONFLICT` | 409 | — | `idempotencyKey` |
| `RUN_IN_FLIGHT` | 409 | — | `runId` |
| `PROVISIONING_REFUSED` | 422 | `REALM_REFUSED`, `PARTICIPANT_REFUSED`, `ADMINISTRATOR_REFUSED` | `participant`, `participantCode` |
| `PROVISIONING_UNKNOWN` | 503 | — | `runId` |
| `PARTICIPANT_UNREACHABLE` | 503 | — | `participant` |
| `IDENTITY_UNREACHABLE` | 503 | — | — |
| `APPROVAL_REQUIRED` | 202 | — | `approvalId` |
| `APPROVAL_INVALID` | 422 | `NOT_FOUND`, `ALREADY_USED`, `SAME_PRINCIPAL`, `WRONG_TARGET` | `approvalId` |

Two contract properties carried over deliberately from the money paths, because
a caller that gets them wrong here corrupts a tenant instead of a transfer:

**The retry rule, both halves.** Any 4xx is terminal for that idempotency key.
Any 5xx, timeout or connection failure means the outcome is *unknown* and the
caller must retry the same key until it gets a definitive answer.
`PROVISIONING_UNKNOWN` is the explicit form of this: it is a 503 carrying the run
id, and it means *the run exists and its answer is not yet known*. It is never a
success and never a failure, and the console must not render it as either.

**Not-found and wrong-tenant are indistinguishable** — inherited convention. Here
it means an operator without `platform:tenants:read` learns nothing about which
tenant ids exist.

## 4. What this API deliberately does not offer

- **No endpoint returns anything about a tenant's customers, accounts, products
  or money.** There is no such read, and adding one is a change to `design.md`
  §2, not an addition to this file.
- **No endpoint deletes a provisioned tenant.** `DELETE` applies to drafts only.
- **No endpoint returns a stored client secret.** Rotation returns the new value
  once; there is no read.
- **No endpoint provisions on behalf of a prospect.** Every caller is an operator
  with a platform-realm token; self-service onboarding is ADR 0015's revisit
  clause, not an unbuilt row in this table.
