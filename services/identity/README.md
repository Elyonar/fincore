# Identity Service

**Status: DRAFT design, no code.** Recorded by
[ADR 0018](../../docs/adr/0018-first-party-identity-service.md) — identity
becomes a first-party deployable and Keycloak is retired. No domain code lands
while the design is DRAFT, and Keycloak remains the sanctioned development
path until the swap slice in [`docs/design.md`](docs/design.md) §7 is
implemented and green.

## Purpose

The platform's identity provider: verifies staff and service credentials,
mints the tokens every other service already verifies via `libs/auth`, owns
the staff directory that Core's administration surface drives, and holds
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
| [`docs/api.md`](docs/api.md) | Planned surface: authentication + service-facing directory, error catalog |
| [`docs/data-model.md`](docs/data-model.md) | Schema, the constraints that carry security, retention |
| [`docs/threat-model.md`](docs/threat-model.md) | Threats → controls → the suite that proves each |
| [`docs/testing.md`](docs/testing.md) | Every suite, all PLANNED, none gating |

## Known limitations

All of them, honestly: **nothing is built.** When built to the swap slice:
no MFA (phase 2), no message-delivered password reset (notification delivers
nowhere yet; admin reset covers it), no customer identity, no federation —
each a stated decision in the design, not an oversight. Independent security
review gates any non-development deployment.
