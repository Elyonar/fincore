# Core — The UI Runway

**Status:** AGREED v1.24 (2026-08-10) — amendments via [`CHANGELOG.md`](CHANGELOG.md)

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
path locked out of deployed profiles; the realm template + provisioning
script; the reverse proxy in compose; Core's ledger-read proxy endpoints; the
Phase 0 list/search endpoints named in §3.

**Out, deliberately:** partner APIs, API keys, per-consumer rate limits,
webhooks, open-banking surfaces (Phase 4+, per ADR 0014's revisit clause);
the tenant-admin provisioning dashboard (roadmap); offline/queue behavior for
tellers (client-side concern, PRD §4.7.4); any new domain capability — this
runway adds *shapes over existing facts*, never new business rules.

## 2. Decisions

**Login is Keycloak's, screens are ours, tokens are the only bridge.** The SPA
authenticates against the tenant's realm (authorization code + PKCE — the
public-client flow; no client secret in a browser), holds the short-lived
access token, and sends it as a bearer to one origin. Nothing about the
authenticated session lives server-side in Core: every request is judged on
its token, exactly as `libs/auth` already judges it.

**One origin, path-routed.** The reverse proxy serves the SPA's origin and
routes `/api/core/*` → Core and `/api/ledger/*` → nothing, because that route
must not exist: ledger reads clients need arrive as Core endpoints (ADR 0014).
Keycloak is reachable on its own origin for the OIDC flow only.

**The ledger-read proxy is thin and shape-preserving.** Core's statement and
balance endpoints forward to the ledger's read API through the existing
`HttpLedgerClient`, translating only identity (bearer propagated, tenant from
token) and errors (the ledger's catalog maps into Core's, prefixed, the way
saga refusals already carry ledger reasons). No caching, no reshaping beyond
envelope conventions: the ledger's statement contract — period-bounded,
`opening + Σ movements = closing`, final vs interim — is the product feature,
and Core must not blur it.

**Jwt-mode is CI-tested with a real realm.** A compose-profile integration
lane starts Keycloak with the template realm, mints real tokens, and drives
one money path and one lending path end-to-end — so "works with real tokens"
is a claim with a test, and the dev-mode lockout keeps its existing suite.

## 3. The read audit — Phase 0 screens against today's surface

Screens from PRD Phase 0 + §4.7.4. **Built** = the row exists in `api.md`
today. **Planned** rows are this design's implementation checklist and enter
`api.md` only when true.

| Screen | Needs | State |
|---|---|---|
| Login | realm per tenant, PKCE flow | **Built** (`keycloak/realm-template.json` + `scripts/provision-tenant.sh`) |
| Teller till | open/close till, till list | **Built** (`/v1/tills…`) |
| Teller till | the till's day: movements + running position | **Built** (`GET /v1/tills/{id}/activity?date=`) |
| Customer search | find by name/external ref | **Built** (`GET /v1/customers?q=&page=`) |
| Customer 360 | profile, tier, status | **Built** (`GET /v1/customers/{id}`) |
| Customer 360 | accounts with balances | **Built** (`GET /v1/customers/{id}/accounts`, ledger-read proxy) |
| Statement print | period statement, final/interim label | **Built** (`GET /v1/accounts/{ledgerAccountId}/statement?from=&to=`, byte-for-byte proxy) |
| Deposit / withdraw / transfer | the money paths | **Built** |
| Reversal + approvals | pending approvals for this checker | **Built** (`GET /v1/approvals/pending`) |
| Loan desk | applications by state / awaiting my signature | **Built** (`GET /v1/loan-applications?state=&awaiting=me&page=`) |
| Loan desk | a customer's loans | **Built** (`GET /v1/customers/{id}/loans`) |
| Loan account | balances, schedule, payoff | **Built** (`/v1/loans/{id}…`) |
| Loan account | repayment history | **Built** (`GET /v1/loans/{id}/repayments`) |
| Product config | products and versions | **Built** (`GET /v1/products`) |
| Report preview | PAR by bucket × product × officer × unit | **Built** (`GET /v1/portfolio/par`) |
| Ops | unresolved-outcome cases | **Built** (`GET /v1/ops/cases`) |

Conventions the planned rows inherit unchanged: deny-by-default permission per
endpoint (existing vocabulary — `customers:read`, `tills:read`, `loans:read`,
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
  refuses to start; the jwt integration lane with the template realm.
- **keycloak/**: `fincore-dev` generalized into a versioned template;
  provisioning script (realm + tenant-registry row, one loud failure).
- **`api.md` / `testing.md`**: planned rows and suites move in as they become
  true, never before.

## 5. Testing

The jwt lane (real realm, real tokens, one money path + one lending path);
propagation (ledger receives and attributes the originating principal;
worker-context postings attribute the system principal); the proxy preserves
the statement contract byte-for-byte including the interim label; every
planned list endpoint gets the standing probes — deny-by-default,
cross-tenant invisibility, keyset pagination stability under concurrent
writes; and the dev-mode lockout suite keeps passing untouched.
