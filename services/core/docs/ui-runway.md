# Core — The UI Runway

**Status:** AGREED v2.4 (2026-08-11) — amendments via [`CHANGELOG.md`](CHANGELOG.md)

The bridge between the APIs that exist and the client apps that will consume
them ([ADR 0014](../../../docs/adr/0014-ui-runway.md)): identity made real,
the edge as configuration, the ledger-read proxy decision, and the
screen-by-screen read audit. Design agreed before code, like everything here;
endpoint rows move into `api.md` only as they are built, because
`ApiSurfaceCatalogTest` fails a documented-but-unbuilt route and that is a
feature this document must not fight.

---

## 1. Scope

**In:** end-to-end jwt identity (login → token → Core → ledger) with the dev
path locked out of deployed profiles; tenant provisioning; the reverse proxy in
compose; Core's ledger-read proxy endpoints; the Phase 0 list/search endpoints
named in §3.

**Out, deliberately:** partner APIs, API keys, per-consumer rate limits,
webhooks, open-banking surfaces (Phase 4+, per ADR 0014's revisit clause);
the tenant-admin provisioning dashboard (roadmap); offline/queue behavior for
tellers (client-side concern, PRD §4.7.4); any new domain capability — this
runway adds *shapes over existing facts*, never new business rules.

## 2. Decisions

**Login is the identity service's, screens are ours, tokens are the only
bridge.** The client posts credentials to the identity service, holds the
short-lived access token, and sends it as a bearer to one origin. Nothing about
the authenticated session lives server-side in Core: every request is judged on
its token, exactly as `libs/auth` already judges it.

> **Amended v2.4.** This decision originally read "login is Keycloak's" and
> described an authorization-code + PKCE flow against a realm per tenant.
> [ADR 0018](../../../docs/adr/0018-first-party-identity-service.md) retired
> Keycloak and made identity first-party, which changes *who issues the token*
> and nothing else about this runway: one origin, bearer-only, no server-side
> session, every request judged on its token. The shape survived the swap
> because it never depended on the issuer.

**One origin, path-routed.** The reverse proxy serves the SPA's origin and
routes `/api/core/*` → Core and `/api/ledger/*` → nothing, because that route
must not exist: ledger reads clients need arrive as Core endpoints (ADR 0014).
`/api/identity/*`, `/api/customer/*`, `/api/product/*` and
`/api/notification/*` route to their own deployables through the same origin.

**The ledger-read proxy is thin and shape-preserving.** Core's statement and
balance endpoints forward to the ledger's read API through the existing
`HttpLedgerClient`, translating only identity (bearer propagated, tenant from
token) and errors (the ledger's catalog maps into Core's, prefixed, the way
saga refusals already carry ledger reasons). No caching, no reshaping beyond
envelope conventions: the ledger's statement contract — period-bounded,
`opening + Σ movements = closing`, final vs interim — is the product feature,
and Core must not blur it.

**Jwt-mode is CI-tested with real tokens.** `JwtEndToEndTest` mints tokens the
way the identity service does, drives one money path end-to-end, and proves the
dev-mode lockout — so "works with real tokens" is a claim with a test rather than
a hope.

## 3. The read audit — Phase 0 screens against today's surface

Screens from PRD Phase 0 + §4.7.4. **Built** = the row exists in `api.md`
today. **Planned** rows are this design's implementation checklist and enter
`api.md` only when true.

| Screen | Needs | State |
|---|---|---|
| Login | credentials → token, per tenant | **Built** (`POST /api/identity/v1/auth/login`; tenants seeded from the manifest by `bootstrap/seed-registries.sh`, ADR 0016) |
| Teller till | open/close till, till list | **Built** (`/v1/tills…`) |
| Teller till | the till's day: movements + running position | **Built** (`GET /v1/tills/{id}/activity?date=`) |
| Customer search | find by name/external ref | **Built** (`GET /v1/customers?q=&page=`) |
| Customer 360 | profile, tier, status | **Built** (`GET /v1/customers/{id}`) |
| Customer 360 | accounts with balances | **Built** (`GET /v1/customers/{id}/accounts`, ledger-read proxy) |
| Statement print | period statement, final/interim label | **Built** (`GET /v1/accounts/{ledgerAccountId}/statement?from=&to=`, byte-for-byte proxy) |
| Deposit / withdraw / transfer | the money paths | **Built** |
| Reversal + approvals | pending approvals for this checker | **Built** (`GET /v1/approvals/pending`) |
| Product config | products and versions | **Built** (`GET /v1/products`) |
| Ops | unresolved-outcome cases | **Built** (`GET /v1/ops/cases`) |

Conventions the planned rows inherit unchanged: deny-by-default permission per
endpoint (existing vocabulary — `customers:read`, `tills:read`,
`transfers:read`; no new permissions), tenant from token, absent and
another-tenant's indistinguishable, money as decimal strings, keyset
pagination (`page` is an opaque cursor, never an offset).

## 4. What this changes elsewhere (the implementation PR's checklist)

- **Ledger**: adopts `libs/auth` jwt mode; tenant header removed; caller
  allowlist enforced on writes; `initiatedBy` from the propagated principal.
  Its own CHANGELOG entry and header bump — a contract change, versioned where
  its consumers look.
- **libs/auth**: outbound propagation (the recorded "not built yet" item) —
  forward-the-bearer plus service identity, consumed by Core's ledger client.
- **Compose/CI**: reverse proxy service; jwt-mode profile where dev identity
  refuses to start; the jwt integration lane.
- **Tenant provisioning**: the manifest in `bootstrap/tenants.json` and the one
  script that registers every tenant with every deployable that gates on a
  registry (ADR 0016). A tenant missing from any one of them authenticates
  perfectly and then meets a bodiless 404 everywhere.
- **`api.md` / `testing.md`**: planned rows and suites move in as they become
  true, never before.

## 5. Testing

The jwt lane (real tokens, one money path);
propagation (ledger receives and attributes the originating principal;
worker-context postings attribute the system principal); the proxy preserves
the statement contract byte-for-byte including the interim label; every
planned list endpoint gets the standing probes — deny-by-default,
cross-tenant invisibility, keyset pagination stability under concurrent
writes; and the dev-mode lockout suite keeps passing untouched.
