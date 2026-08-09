# Identity — Testing

**Status:** DRAFT v0.1 (2026-08-09)

Per platform convention every suite is marked IMPLEMENTED, PARTIAL or DEFERRED,
and only IMPLEMENTED suites gate merges. The swap slice and TOTP are built and
carry the suites marked IMPLEMENTED below; the remaining adversarial assertions
(wire-byte refusal equality, timing, multi-instance throttle, full MFA
end-to-end) are honestly PARTIAL or PLANNED — the controls in
[`threat-model.md`](threat-model.md), and preconditions of AGREED, not of this
first landing.

**Sandbox note:** these suites were authored and hand-reviewed but not run in
the build environment they were committed from (no Maven repository access
there). CI is the gate.

Real PostgreSQL throughout, never in-memory. Tests named after the guarantee
they prove.

| Suite | Proves | Status |
|---|---|---|
| Schema presence | Every trigger, index, policy, composite FK exists and fires | IMPLEMENTED (`SchemaTest`) |
| Schema enforcement | Raw SQL cannot update/delete `auth_events`; a username is unique per tenant; no `signing_keys` table exists | IMPLEMENTED (`SchemaTest`) |
| ArchUnit + empty-import canary | No reach into another deployable; libs/auth is test-scope only; canary fails on empty import set | IMPLEMENTED (`BoundaryTest`) |
| **Token-contract parity** | A token minted here resolves through `libs/auth` into an `IdentityContext` identical to the realm-minted golden token; a service token carries no tenant claim; an action token is inert | IMPLEMENTED (`TokenParityTest`) — the acceptance test of ADR 0018 |
| TOTP (RFC 6238) | Generated codes match the RFC's published vectors; a live secret verifies its own code and rejects a wrong one; base32 round-trips | IMPLEMENTED (`TotpTest`) |
| Login lifecycle & refusals | Temporary credential → forced change → tokens; the old credential dies; unknown user and wrong password refuse identically; policy enforced; refresh rotation revokes the family on reuse; a correct password during lockout refuses like a wrong one | IMPLEMENTED (`LoginFlowTest`) |
| Uniform-refusal shape (wire bytes) | Byte-identical error body, status and headers across causes; timing within documented tolerance | PARTIAL — behavioural refusal proven in `LoginFlowTest`; the byte/timing assertion is PLANNED |
| Throttle & lockout (multi-instance) | Two instances agree via rows; nonexistent accounts throttle identically | PARTIAL — single-instance lockout proven; the cross-instance and enumeration-equality assertions are PLANNED |
| MFA end-to-end | Enrol → activate → login returns MFA_REQUIRED → verify grants; a recovery code is single-use; step-up mints an acr=mfa token; disable requires a factor | PLANNED |
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
