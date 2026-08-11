# ADR 0014 — The edge is configuration, reads come through Core, and identity gets real before the first screen

**Status:** Accepted · 2026-08-08
**Supersedes:** nothing. Schedules what [ADR 0010](0010-keycloak-realm-per-tenant.md)
already promised, and records the decisions the first external consumer forces.

## Context

Every domain service the Phase 0 client apps need already exists: the ledger,
Core's five modules (customer, product, organization, orchestration,
app), and notification. Deposits, withdrawals, transfers, reversals, tills and
period-bounded statements are all served by APIs
that are live and tested today.

What does not exist is the layer between those APIs and a browser:

1. **Identity is dev-mode everywhere.** `libs/auth` is built, jwt mode refuses
   to start without an issuer, and the dev resolver is double-locked — but no
   deployed profile actually runs jwt end-to-end, no realm is provisioned from
   a template, and the compose file's own comments say "headers rather than
   tokens." A client app's first screen is a login screen.
2. **The ledger still trusts a tenant header.** ADR 0010 recorded that once
   `libs/auth` existed the ledger would consume it and the header path would be
   removed. `libs/auth` has existed for three services' worth of work; the
   header survives, the ledger enforces none of the caller roles its `api.md`
   names, and nothing propagates the originating principal into posting
   attribution. This was an acceptable posture while every caller was a test.
   It is not one while a browser exists.
3. **Nobody has decided how a client reads the ledger.** Statements and
   balances live behind the ledger's API; clients must never address the
   ledger; no rule says what happens instead.

PRD §9 answers the *when*: the API Gateway arrives "when the first external
consumer exists," and the client apps are that consumer. PRD Phase 1 answers
the *what*: "edge TLS and token validation are gateway configuration and the
shared authorization library, not a service to build."

## Decision

### The edge is configuration, and stays that way

One reverse proxy (compose service, TLS termination, single origin for the
SPA, CORS, request size limits; per-consumer rate limits and API keys arrive
with partner APIs, not before). It routes by path prefix and passes the bearer
token through untouched. **The edge authenticates nothing and authorizes
nothing** — token validation happens in every service via `libs/auth` exactly
as it does today, so a misconfigured proxy fails closed, not open. A gateway
*service* (developer portal, key management, webhooks — PRD §4.7.3) is Phase 4+
work and requires a new ADR if it arrives earlier.

### Clients speak to Core; Core proxies the ledger reads it chooses to expose

Hard rule 3 governs writes and is untouched. This ADR adds its read-side
completion: **no client, channel or partner ever addresses the ledger.** The
reads a screen needs — an account's statement, a customer's balances — surface
as Core endpoints, served through Orchestration's existing ledger client, under
Core's permission vocabulary and error catalog. The accepted cost is a proxy
hop on statement reads; the alternative — exposing the ledger's surface at the
edge with its own authorization vocabulary — puts two contracts around the
crown jewel and was rejected. (The Compliance & Reporting service, when it
exists, gets its own read-only ledger path per PRD §6.2 — that is a service,
not a client, and is not this decision.)

### The ledger's tenant header dies, as ADR 0010 promised

The ledger adopts `libs/auth` in jwt mode. Core's ledger client forwards the
originating request's bearer token (outbound propagation, the `libs/auth`
"not built yet" item), and presents its service identity per the deployment's
mTLS posture. The ledger then:

- takes the tenant from the validated token, never from a header;
- enforces its caller allowlist (`requireCallerAnyOf("core")`) on the write
  surface, closing the "does not enforce caller roles" known limitation;
- records `initiatedBy` from the propagated principal, so posting attribution
  is a verified fact rather than a copied string.

Worker-context calls (saga recovery, reconciliation, recognition) carry the
service identity with a system principal, exactly as they already attribute
themselves in Core's own records. This is a ledger contract change and lands
in the ledger's CHANGELOG as its own versioned amendment.

### Realm provisioning is a script before it is a product

A versioned realm template (the dev realm, generalized: job composites,
permission vocabulary, `units` claim mapper) plus a provisioning script that
creates realm + tenant-registry row as one operation that fails loudly rather
than half-succeeds (ADR 0010's consequence, made executable). The tenant-admin
dashboard that drives this through a UI remains roadmap work. Phase 0 needs
exactly one pilot realm, created from the template, checked in as evidence.

### The UI-shaped read surface is named before the first screen is built

The screen-by-screen audit lives in `services/core/docs/ui-runway.md` §3. The
principle recorded here: list and search endpoints are designed as a batch
against the Phase 0 screen inventory, not discovered one 404 at a time — and
their rows move into `api.md` only as they are built, per the catalog tests'
standing rule.

## Consequences

- Compose gains a reverse proxy and a jwt-mode profile in which dev identity
  refuses to start; CI gains a jwt-mode integration path with a throwaway
  realm, so "works with real tokens" is a tested claim.
- The ledger's header contract change is breaking for any caller that is not
  Core; there are none, which is why the change is cheap now and expensive
  later.
- The client apps depend on nothing in this repo but the edge origin and the
  OpenAPI surface — which is the point.

## Revisiting

If a partner/API-key consumer arrives before Phase 4, the edge decision
reopens (rate limits, scopes, key management are consumer-shaped, not
teller-shaped). If Compliance & Reporting lands, its ledger read path is its
own decision and must not silently widen this one.
