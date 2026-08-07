# Keycloak — the local development realm

**Every credential here is fake, published, and worthless outside a laptop.**
That is the point: a stack anyone can clone and log into. `AGENTS.md` hard rule 8
forbids committing security-sensitive specifics, and this directory is the
opposite of one — no real user, no real secret, no deployment detail. A
production realm is provisioned through Keycloak's admin API by the tenant admin
dashboard ([ADR 0010](../docs/adr/0010-keycloak-realm-per-tenant.md)), never from
a file in a public repository.

## Run it

```bash
docker compose --profile identity up -d keycloak
open http://localhost:8180        # admin console — admin / admin
```

Then start the services in JWT mode:

```bash
FINCORE_AUTH_MODE=jwt \
FINCORE_AUTH_ISSUER_URI=http://keycloak:8080/realms/fincore-dev \
docker compose --profile identity up -d --force-recreate core notification
```

Get a token and use it:

```bash
TOKEN=$(curl -s -X POST \
  http://localhost:8180/realms/fincore-dev/protocol/openid-connect/token \
  -d grant_type=password -d client_id=fincore-cli \
  -d username=ada -d password=password | jq -r .access_token)

curl -H "Authorization: Bearer $TOKEN" http://localhost:58081/v1/products
```

## The users

Password for all of them: `password`. They are shaped like jobs rather than like
permissions, because a single all-powerful account teaches nothing about a
platform whose authorization model is deny-by-default and least privilege.
**Trying an endpoint your role does not cover and getting a 403 is the system
working.**

| User | Role | Can | Cannot |
|---|---|---|---|
| `ada` | `job:teller` | take cash, initiate transfers, read customers and products | create a product, approve anything, reverse |
| `bola` | `job:supervisor` | everything a teller can, plus approvals and business reversal | configure products or templates |
| `chidi` | `job:ops` | work the unresolved-outcome queue, read deliveries | **declare an outcome** — only the ledger can |
| `ngozi` | `job:compliance` | read customers, move KYC tiers with a reason, record consent | move money |
| `grace` | `job:admin` | configure customers, products, templates and delivery policy | move money — deliberately |
| `root` | all of the above | everything, for when you are exploring rather than testing a boundary | — |

`job:api-partner` and `machine:notification` exist and are assigned to nobody:
the first is the narrow grant a tenant would give a third party (PRD §4.7.3),
the second is what Notification will present once it fetches a token instead of
sending development headers.

## How a token becomes an authorization

Two protocol mappers do the work, and both are load-bearing:

- **`permissions`** — realm roles, flattened into the array `libs/auth` reads.
  `Authorization.require("products:create")` matches an entry here. Composite
  roles expand, so `job:admin` arrives as the eleven permissions it contains.
- **`tenant_id`** — hardcoded into every token this realm issues, because
  **one realm is one tenant** (ADR 0010). The tenant is therefore a property of
  the realm rather than something a caller supplies: a header-supplied tenant is
  a caller assertion, and every downstream isolation control would faithfully
  enforce the wrong boundary.

The realm's roles are checked against the code: every permission any service
demands has a role here, and no role exists that no endpoint demands.

## Two things that will surprise you

**The issuer is `http://keycloak:8080/...`, not `localhost:8180`.** A token's
`iss` must equal the issuer a service validates against, and the two reach
Keycloak by different names — you curl `localhost:8180`, services resolve
`keycloak` on the compose network. `KC_HOSTNAME` pins the issuer so every token
says the same thing whichever door it was requested through, and services fetch
the signing keys over a name they can actually resolve.

**Nothing you do in the admin console survives a restart.** `start-dev` keeps its
database in memory and `--import-realm` rebuilds this file on every start. That
is deliberate: the realm cannot drift from what the repository says it is. Edit
the JSON, which is also what makes a change reviewable.

## What this does *not* cover

- **The ledger.** It does not import `libs/auth` and still takes a tenant header,
  which it documents as *not authentication*. JWT mode reaches Core and
  Notification only.
- **Service-to-service identity.** ADR 0009 makes the calling service a third
  identity, verified by mutual TLS. There is no mTLS here, so
  `Authorization.requireCallerAnyOf(...)` exists, is unit-tested, and is called
  by nobody — the ledger's allowlist admitting only `core` is still prose.
- **MFA, session policy, federation, per-tenant realms.** One realm, one tenant,
  password grants. PRD §4.10 describes what a real deployment adds.
