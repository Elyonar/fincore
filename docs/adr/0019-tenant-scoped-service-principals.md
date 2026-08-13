# ADR 0019 — A service that reads tenant data holds a tenant-scoped service principal

**Status:** Proposed · 2026-08-12
**Relates to:** [ADR 0009](0009-service-to-service-identity.md) (service identity
is mTLS and distinct from the principal — unchanged, and the reason this ADR is
about the principal only), [ADR 0011](0011-first-consumer-before-phase-three.md)
(the consumer this was found in), [ADR 0018](0018-first-party-identity-service.md)
(the client-credentials flow this extends).

**Scope note.** Per `AGENTS.md` hard rule 8 this records a structural decision
only — no credentials, no secrets, no deployment specifics.

## Context

Notification consumes `transfer.completed` and asks Core two questions before it
can address anybody: which accounts the transaction moved between, and who holds
them. Both are ordinary tenant-scoped reads —
`GET /v1/transactions/{id}` behind `transfers:read`, and
`GET /v1/customers/by-account/{id}` behind `customers:contact`.

Notification asked them with the development identity headers
(`X-Dev-Tenant-Id`, `X-Dev-Principal`, `X-Dev-Permissions`), written when every
service ran the header-trusting dev resolver. ADR 0018 landed, `jwt` became the
default mode, and those headers stopped meaning anything. Core answers 401. The
intake throws — correctly, because an exception is what stops the Kafka offset
being committed — the offset stays put, and the same event is redelivered about
once a second, forever, logging nothing at any level.

The consequence is not a degraded feature. **No notification has ever been
produced in the default deployment posture, and nothing reports that.** The
service's own tests pass because they exercise `EventIntake` against stubs; the
gap lives exactly in the seam between two services, which is the seam ADR 0011
created this consumer to examine.

The obvious repair does not work. Identity's client-credentials token
(`POST /v1/auth/token`, `ServiceClients`) deliberately carries `azp` and **no
tenant claim and no permissions** — it is the shape the ledger's caller allowlist
expects, where the question is only "is this Core" and authorization is a list of
permitted callers rather than a permission set. Presenting one to Core fails on
both counts: `libs/auth` refuses a token with no tenant, and
`Authorization.require` has nothing to check.

## Decision

**A service that reads or writes one tenant's data authenticates as a service
principal minted for that tenant, carrying the permissions its client is
declared to hold.** Concretely, `POST /v1/auth/token` accepts an optional
`tenantId`; when present, identity verifies the tenant is real and active and
mints a token whose claims are the staff shape minus the human parts: `iss`,
`sub` (the client id), `azp`, `jti`, `tenant_id`, and `permissions` taken from
the client's declaration. Omit `tenantId` and the existing tenantless token is
minted, unchanged, for the ledger-allowlist case.

A client's permissions are **declared configuration, seeded like its secret** —
`clientId=perm|perm`, alongside the existing `clientId=ENV_NAME` secret
reference — and stored on `auth.service_clients`. A service cannot ask for more
than it was declared to hold, because it does not ask at all: it presents a
client id and a tenant, and identity decides.

Three properties are load-bearing:

- **The tenant is in the token, never asserted by the caller.** This is the same
  invariant every other surface rests on, and the reason the tenant is a mint
  parameter rather than a header on the call to Core. A service asserting its own
  tenant per request would be precisely the caller-asserted scope this platform
  refuses everywhere else — and a consumer processing another tenant's event
  would faithfully read the wrong institution's customers.
- **The permission set is the client's, not the request's.** Notification is
  declared `customers:contact` and `transfers:read` and can therefore do nothing
  else with the token, including at services it was never meant to call.
- **This is the principal, not the service identity.** ADR 0009's separation
  stands untouched: which system placed the call is still the verified TLS peer,
  still absent until mTLS is deployed, and still the thing the ledger's allowlist
  keys on. This ADR says only who authorized the read.

## Consequences

**What gets better.** The consumer works at all. The dev-header path leaves the
notification service entirely, so there is no configuration in which it is
accidentally trusted. A second consumer arriving later inherits a model rather
than repeating this discovery.

**What is now true and was not.** Identity can mint a token that acts inside a
tenant without a human behind it. That is a real widening and it is why the
permission declaration is configuration seeded at startup and not an API: adding
a capability to a service is a deployment change with a review, not a runtime
call.

**A wart, recorded rather than hidden.** `libs/auth` renders every principal as
`user:<sub>`, so a service principal reads `user:notification` in an audit line.
It is honest about which client acted and dishonest about it being a person.
Changing the prefix touches every service's attribution strings and every test
that asserts one, so it is not done here; it belongs with the mTLS work that
gives the caller a real second identity to render beside it.

**What this does not do.** It does not give services write access to anything —
nothing here mints a token with a money-moving permission, and the two grants
this ADR exists to serve are both reads. It does not replace the ledger's caller
allowlist. It does not make the notification service deliver anything to a
customer: the connector is still unbuilt, and the `log` senders still say so at
startup.
