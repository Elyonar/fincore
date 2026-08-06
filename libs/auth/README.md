# libs/auth — the shared authorization library

> **Mechanics written once, decisions left to the owning service.**

Every fincore service validates tokens, extracts who is calling, and enforces
permissions. Doing that in four places means four chances to get token
verification subtly wrong, and four things to re-audit. This library holds the
mechanics; the decisions stay where the domain knowledge is.

Recorded in [ADR 0009](../../docs/adr/0009-service-to-service-identity.md) and
[ADR 0010](../../docs/adr/0010-keycloak-realm-per-tenant.md); the platform
rationale is PRD §6.3.

**Why it exists now.** `AGENTS.md` says a library is extracted only when a second
consumer exists. Two do: Core needs identity from its first endpoint, and the
ledger needs it to retire the tenant-header posture its own README lists as a
known limitation.

## What it does

- Validates a bearer token against the issuer's published keys — locally, per
  request, with no call to the provider. Identity being briefly down blocks new
  logins, not money movement (PRD §6.1).
- Extracts the **three identities** that travel with a money-path request, each
  verified by a different party:

  | Identity | Answers | Verified by |
  |---|---|---|
  | principal | who asked | the identity provider, via the token |
  | service | which system acted | mutual TLS, via the peer certificate |
  | tenant | whose money | the token's `tenant_id` claim |

  Mutual TLS authenticates a **process**, so the service identity is the
  deployable (`core`), never a module inside it. A rule about which *module* may
  call something is enforced by that deployable, not by its callee.

- Scopes that context to the request and **always clears it**, including on
  exception.
- Provides `require(permission)` and `requireCallerAnyOf(services…)`.

## What it deliberately does not do

**Domain authorization.** Whether an approval tier covers an amount, whether
maker differs from checker, whether a saga's state permits reversal, which
permission a given endpoint demands — these live in the service that owns the
rule, because only it can know them. A shared library holding them would need to
know every domain, and would become the single place a change to any service's
rules has to land.

It also holds no database, no HTTP client, and no domain types. The dependency
list in `pom.xml` is the control a reviewer reads.

## Using it

```java
// At the top of a handler — deny by default, so an endpoint nobody
// remembered to protect is closed rather than open.
Authorization.require("transfers:create");

// A service's own caller allowlist. Enforcement belongs to the owning
// service; the gateway authenticates but never decides.
Authorization.requireCallerAnyOf("core");

// Attribution, as two separate facts. Examiners ask who authorized an action
// and which system performed it as different questions.
String initiatedBy = Authorization.initiatedBy();   // user:ada.o@branch-01
String executedBy  = Authorization.executedBy();    // core

// Never a header, never a request body.
UUID tenantId = Authorization.tenantId();
```

Configuration:

```yaml
fincore:
  auth:
    mode: jwt                                   # the only deployable mode
    issuer-uri: http://localhost:8180/realms/acme-bank
    tenant-claim: tenant_id                     # defaults shown
    principal-claim: preferred_username
    permissions-claim: permissions
```

`mode: jwt` **refuses to start without an issuer** rather than accepting tokens
nobody verified.

## Development mode, and the two locks on it

`mode: dev` reads identity from `X-Dev-*` headers and **verifies nothing** — any
caller can claim any tenant and any permission. ADR 0010 permits it as a stand-in
only while it cannot be enabled by accident, so:

1. `fincore.auth.mode=dev` **and** an active profile from `dev`/`test`/`local`.
   Setting the mode alone fails startup with an explanation. One stray
   environment variable is not enough.
2. A loud startup banner whenever a non-verifying resolver is active.

Both are tested — "impossible to enable accidentally" is a claim, and
`DevIdentityResolverTest` is what makes it one. The shape of the problem comes
from the ledger's `log` event adapter: a component that silently does nothing
while the system reports itself working.

## Local Keycloak

```bash
docker compose --profile identity up -d keycloak   # http://localhost:8180
```

Development mode, no persistence, throwaway credentials. Real deployments
configure this properly, and those specifics live with the deployment rather
than in this repository.

## Tests

```bash
./mvnw -pl libs/auth test
```

The suite covers what the library actually promises: no context denies, a
missing permission denies, an unverified caller is on no allowlist, permissions
cannot be widened after a context is built, the context survives neither an
exception nor the end of a request, **a header can never override the token's
tenant**, a rejected token does not leak why it was rejected, and dev mode
refuses to start unsanctioned.

## Not built yet

- **Outbound propagation.** A service calling another currently presents its own
  certificate; carrying the originating principal onward is designed
  (PRD §6.3, "signed headers / propagated JWT") and not implemented. Core needs
  it when it calls the ledger, so it lands with Core's ledger client.
- **The permission vocabulary.** Kept in the services for now. It centralizes in
  Identity when there is a second service to disagree with the first.
- **Realm provisioning.** The tenant-admin flow that creates a realm per tenant
  is product work, not library work.
