# Identity Service

**Status: implemented, merged and running (design DRAFT pending AGREED).**
Recorded by [ADR 0018](../../docs/adr/0018-first-party-identity-service.md) —
identity is a first-party deployable and Keycloak is retired. **20 tests green
against real PostgreSQL**, running in CI like every other service.

Built: login, forced credential change, refresh rotation with family revocation,
logout and revoke-all, service tokens (ADR 0019), JWKS with kid rotation, TOTP
MFA, **and the staff directory** — users, roles, permissions, job titles and
staff numbering, which Core's `admin` module proxies rather than duplicating.

> **A deployment must set `fincore.identity.signing.private-key-pem`.** Without
> it the service generates an ephemeral development key and says so at startup —
> which means every token it ever issued becomes invalid the moment it restarts.
> Independent security review still gates any non-development deployment.

## Purpose

The platform's identity provider: verifies staff and service credentials,
mints the tokens every other service already verifies via `libs/auth`, owns
the staff directory Core's administration surface drives, and holds
per-tenant authentication policy. Client-driven by product decision — every
flow is a first-party API; there is no hosted login page.

## Boundaries

States what a caller holds; never decides what a holding permits — enforcement
stays deny-by-default in owning services. No product surface (Core owns
admin-surface §5). No permission authorship (the catalog is platform code,
ADR 0017). No customer credentials in v1. No money. Full list:
[`docs/design.md`](docs/design.md) §2.

## Docs

| Doc | Contents |
|---|---|
| [`docs/design.md`](docs/design.md) | Decisions, token contract, bootstrap, swap slice |
| [`docs/api.md`](docs/api.md) | The surface: authentication + service-facing directory, error catalog |
| [`docs/data-model.md`](docs/data-model.md) | Schema, the constraints that carry security, retention |
| [`docs/threat-model.md`](docs/threat-model.md) | Threats → controls → the suite that proves each |
| [`docs/testing.md`](docs/testing.md) | Every suite and its status |

## The full API (Swagger)

The comprehensive surface is authored as an OpenAPI 3.1 contract at
[`src/main/resources/static/openapi.yaml`](src/main/resources/static/openapi.yaml)
— authentication, MFA/2FA, email & phone verification, step-up, password reset,
sessions & devices, directory admin, roles, policy and audit, each tagged
`x-status: built | planned`. The running service renders it at `/docs`.

## Known limitations

Honest edges. **Built:** the ADR 0018 swap slice (login, forced change, refresh
rotation with theft detection, logout/revoke-all, service tokens, JWKS) and
**TOTP 2FA** (enrol, activate, verify, step-up, recovery codes, disable).
**Delivery-gated (not yet functional):** email/phone verification, SMS OTP and
self-service password reset — they send a code to a person, and the platform's
messaging connector does not exist yet (notification senders are log adapters by
decision); administrator-initiated reset covers the gap. **Planned:** policy and
audit query, customer identity, and federation.

**The design docs are DRAFT while the service runs**, which the platform
convention otherwise forbids. It is deliberate and named in
[`AGENTS.md`](../../AGENTS.md): ADR 0018 is the decision of record and this
surface is still moving, so treat the ADR as the contract rather than the DRAFT
docs. They go AGREED when the surface settles.
