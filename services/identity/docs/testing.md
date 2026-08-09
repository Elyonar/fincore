# Identity — Testing

**Status:** DRAFT v0.1 (2026-08-09)

Per platform convention every suite is marked IMPLEMENTED, PARTIAL or DEFERRED,
and only IMPLEMENTED suites gate merges. **Everything below is PLANNED** — the
service has no code. The list exists now because the design is only agreeable
if its verification is stated with it; several suites are the controls in
[`threat-model.md`](threat-model.md) and are preconditions of calling the
implementation done, not follow-ups.

Real PostgreSQL throughout, never in-memory. Tests named after the guarantee
they prove.

| Suite | Proves | Status |
|---|---|---|
| Schema presence | Every trigger, index, policy, composite FK exists and fires | PLANNED |
| Schema enforcement | Raw SQL cannot update/delete `auth_events`, cannot express a cross-tenant FK, cannot duplicate a token digest | PLANNED |
| ArchUnit + empty-import canary | Module boundaries, no banned time types, no float near anything, canary fails on empty import set | PLANNED |
| **Token-contract parity** | A token minted here resolves through `libs/auth` into an `IdentityContext` identical to the realm-minted golden token; a service token passes the ledger's caller rules unchanged | PLANNED — the acceptance test of ADR 0018 |
| Uniform-refusal shape | Unknown user, wrong password, disabled, locked: byte-identical error body, status, headers; timing within documented tolerance | PLANNED |
| Rotation & theft | Refresh rotates; reuse of a rotated token kills the family; both live and rotated tokens refuse afterwards; password change and revoke-all kill families | PLANNED |
| Throttle & lockout | Progressive delay engages; lock is silent; nonexistent accounts throttle identically; two instances agree via rows | PLANNED |
| Forced change | Temporary credential yields only the action grant; the grant is single-purpose and consumed; a real token appears only after the change | PLANNED |
| Key rotation | Overlap window honours outstanding tokens; expired-key tokens refuse; JWKS serves both keys during overlap | PLANNED |
| Cross-tenant / foreign-issuer | RLS bleed probes on every tenant-scoped table; a token from a differently-keyed issuer fails verification | PLANNED |
| Grant rules | Catalog-only permissions; grantor rule; last-administrator refusal including the concurrent race | PLANNED |
| Seeding | Malformed manifest refuses whole; idempotent re-run; additive only; temporary credential surfaced once | PLANNED |
| Error catalog | `ErrorCodeCatalogTest` both directions from day one | PLANNED |
| API surface | `ApiSurfaceCatalogTest` set comparison once the first route exists | PLANNED |
| End-to-end swap | Compose stack minus Keycloak: seeded admin logs in, changes password, calls a real Core endpoint with the minted token; `JwtEndToEndTest`'s scenario re-pointed at the real issuer | PLANNED |

Deferred with reasons, stated now: performance/soak (no load target exists yet
for login); penetration testing is external review, tracked as a deployment
gate rather than a CI suite; MFA suites arrive with phase 2.
