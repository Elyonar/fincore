# ADR 0018 — Identity is a first-party service; Keycloak is retired

**Status:** Proposed · 2026-08-09
**Supersedes:** [ADR 0010](0010-keycloak-realm-per-tenant.md) (one Keycloak
realm per tenant). Overrides the build decision of PRD §4.10 ("implemented on
Keycloak, not built from scratch") until the PRD is revised, per the standing
rule that an accepted ADR overrides an older PRD section.
**Relates to:** [ADR 0009](0009-service-to-service-identity.md) (unchanged —
tenant from token, ledger allowlist), [ADR 0016](0016-tenant-bootstrap-manifest.md)
(the manifest gains a fourth reader), [ADR 0017](0017-tenant-defined-roles.md)
(same split, different directory beneath it).

**Scope note.** Per `AGENTS.md` hard rule 8 this records a structural decision
only — no credentials, no production flows, no deployment specifics. The
service's API contract, data model and threat model live in
`services/identity/docs/`, DRAFT until AGREED.

## Context

PRD §4.10 chose Keycloak deliberately, and its rationale was sound when it was
written: authentication is commodity software proven by hostile exposure;
realms map naturally onto tenants; the pedigree passes bank due diligence;
everything downstream speaks OIDC so the provider stays swappable.

Two product decisions made since then change the ground under that rationale,
and this ADR is the honest reckoning with both.

**First: authentication is client-driven.** Every client — the tenant portal,
mobile, CLI, API consumers — authenticates by calling this platform's own API.
There is no hosted login page and no browser redirect to an identity product's
UI. This is a product decision about what an institution's clients experience,
and it removes from play exactly the part of Keycloak that a decade of hostile
exposure has hardened: the browser-facing Authorization Code + PKCE flow that
`fincore-web` was configured for. What remains for an API-driven client is the
Resource Owner Password Credentials grant — removed from OAuth 2.1, disabled on
`fincore-web` by our own design, and structurally unable to carry the flows the
platform already depends on: the forced first-use password change that ADR 0016
seeds, OTP challenges, WebAuthn. Serving client-driven authentication *through*
Keycloak therefore means writing a first-party credential-handling facade in
front of its token and admin APIs. At that point the security-sensitive surface
— the part that must be reviewed like the money path — is our code either way,
and Keycloak is reduced to a user database we operate behind it.

**Second: one deployment serves one institution.** The operating model is an
instance per institution; a platform operator running many institutions runs
many instances, and any fleet-management layer sits above them (that layer is
where the deferred ADR 0015 control plane would live — outside the stack, not
inside it). Realm-per-tenant existed to give each tenant its own policy inside
a *shared* identity tier. In an instance-per-institution model, per-tenant
policy is a row in a tenant-scoped table, and isolation between institutions is
not a realm boundary but a key boundary: each instance signs with its own keys,
and a token from one instance fails verification at another outright.

**Third, and decisive: the platform's dependency on Keycloak is four claims
wide.** Verified in source: `libs/auth` resolves identity from an RS256 JWT
checked against a published JWKS — issuer, `tenant_id`, `preferred_username`
(falling back to `sub`), `permissions`, `units`, plus `jti`. The ledger's
service-caller rule reads `azp`. No service reads realm structure, no service
calls an admin API, and the availability posture (PRD §6.1: verification is
local; if Identity is briefly down, issued tokens keep working and money keeps
moving) is a property of local JWKS verification, not of Keycloak. Whatever
mints that token is a drop-in issuer.

Meanwhile the parts of Keycloak the platform was still going to lean on are
either unused — brokering, SAML, social login, consent, the account console —
or were always going to be first-party code regardless: ADR 0010 itself rules
that tenants never see Keycloak, so the entire user- and role-administration
surface (admin-surface.md §5) is ours, and PRD §4.10's requirement that user
and role changes are maker-checked is a workflow Keycloak's admin API cannot
participate in and our approval machinery already implements.

## Decision

### Identity is a fourth deployable, `services/identity`

A first-party service owns credential verification, token minting, session and
refresh lifecycle, the staff directory, and per-tenant authentication policy.
It is a deployable in the full ADR 0006 sense: its own process, its own
database, its own release cycle, held to the entire service-scaffold checklist
— tenancy pattern included, because the tenancy machinery is defense in depth
and capability preserved, not dead weight, even when an instance carries one
tenant.

### The token contract is the acceptance boundary, and it does not move

The service mints exactly the token the platform already verifies: RS256,
published JWKS, the same claims, `azp` on service credentials, no tenant claim
on service credentials. `libs/auth` does not change; the ledger's caller rules
do not change; no controller changes. That invariance is the test of the swap,
not a hope about it.

### Composition over invention

No novel cryptography and no bespoke protocol. Password hashing, token signing
and randomness come from vetted libraries already on the platform's classpath;
the flows are the boring, standard ones. The correctness argument the PRD
sourced from "a decade of hostile exposure" is re-sourced from the regime every
money-path service here already lives under: an AGREED design, a threat model,
catalog tests that reconcile docs against code in both directions, adversarial
suites written to break the thing, and independent security review before any
non-development exposure. This is a real transfer of responsibility from Red
Hat's patch stream to this repository, and it is stated as such rather than
minimised: it is the cost of the client-driven constraint, paid where the code
can be reviewed, instead of paid as a facade over software whose hardened
surface we no longer use.

### The platform's product principles are amended, not ignored

Constitution-level guidance says *adopt commodity, build differentiation* and
*never fork commodity security software*. This ADR forks nothing — it declines
to adopt, for the narrow case where the commodity's differentiating surface
(hosted, redirect-based login) is excluded by product decision and the
remainder would be wrapped in first-party security-critical code anyway. The
low-lock-in property the PRD valued is preserved in the direction that matters:
the token stays a standard JWT verified via standard JWKS, so a packaged or
certified IdP can be reinstated *behind the same contract* if a revisit trigger
fires — the same swappability the PRD claimed for the opposite direction.

### The manifest gains a fourth reader; realm rendering is retired

Identity seeds from `bootstrap/tenants.json` at startup exactly as ADR 0016
prescribes for the other three: idempotently, additively, refusing a malformed
manifest as a whole. Seeding creates the tenant row and the one
super-administrator with a temporary credential and a forced change on first
use. `render-realms.sh`, the realm template, the import directory and the
Keycloak compose service are deleted when the swap lands — two paths to the
same state means one is wrong and nobody knows which. Secret handling is
unchanged: generated at seed time, surfaced once, never committed.

### The permission catalog moves to code, which ADR 0017 already requires

The realm template is today the second authoritative copy of the permission
vocabulary. ADR 0017 already names a code-level catalog with a reconciling test
as its precondition; this ADR consumes that catalog as the identity service's
source of grantable permissions and deletes the template copy. One list,
tested against `require` call sites, served by the catalog endpoint.

### Administration stays where it was designed

admin-surface.md §5 (AGREED, Core v1.20) keeps its endpoint rows, its
maker-checker rule, its refusals and its revocation-window property. What
changes is the directory beneath it: Core calls the identity service's
service-facing directory API instead of a realm admin API. Tenants never see
the directory API, exactly as they were never to see Keycloak; the edge routes
only the authentication surface.

## Consequences

- **A fourth deployable and a fourth database.** The full scaffold checklist
  applies: Flyway, two database roles, RLS enabled and FORCEd, schema-presence
  and schema-enforcement suites, error catalog with its reconciling test,
  startup summary, metrics for every stated alarm.
- **`libs/auth` unchanged** — and `JwtEndToEndTest`-style proof extends to
  tokens minted by the real issuer, closing the gap where CI only ever verified
  stub-minted tokens.
- **ADR 0016 and `tenant-bootstrap.md` simplify on acceptance:** the realm
  half of the bootstrap disappears; the registry half gains one participant.
  The "editing an existing realm does not take effect" edge — the sharpest in
  that design — goes with it, because seeding and policy live in ordinary
  tenant-scoped rows.
- **Security responsibility is owned in-repo.** The threat model and the
  adversarial suites are a precondition of AGREED, not follow-up work;
  independent security review is a precondition of any non-development
  deployment. Transport security at the edge remains a precondition it was
  under Keycloak too.
- **PRD §4.10 needs a revision PR** reflecting this decision; until then this
  ADR governs.
- **The interim is explicit:** Keycloak remains the sanctioned dev path until
  `services/identity`'s design is AGREED and its first slice is implemented and
  green. There is no period with two sanctioned issuers.

## Revisiting

Reinstate a packaged IdP — behind the unchanged token contract — if any of
these fires:

- **An institution requires federation** with an existing directory or
  enterprise SSO (SAML/OIDC brokering). That is the commodity's real
  differentiation, and the day it is needed is the day adopting it pays again.
- **A regulator or partner mandates a certified identity product.**
- **Authentication scope outgrows a small service** — hardware attestation,
  federation protocols, certification programs — and the maintenance burden
  stops being the narrow, boring core this ADR banks on.
