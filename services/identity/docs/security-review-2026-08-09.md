# Identity — security & completeness revalidation (2026-08-09)

A loophole-and-gap audit of the auth/identity work to date, read against the
source, at the standard a core banking platform is held to. Findings are ranked
by severity. "Loophole" = a weakness in what is built; "Gap" = a capability a
bank needs that is not built yet.

**Overall:** the built core (token contract, refresh rotation with theft
detection, uniform refusal, RLS, TOTP) is sound in shape. The issues below are
real and worth closing before a real client, but none indicates a broken
foundation. The single most important item is not a code bug — it is that TLS
must terminate at the edge before any credential-accepting endpoint is exposed
beyond dev.

---

## Critical — close before any non-dev exposure

**C1 · Transport is plaintext in the stack.** Every service speaks HTTP; the
edge listens on 80. A client-driven auth API that accepts passwords and returns
tokens over plaintext is the whole ballgame. *Fix:* TLS at the edge (already the
stated gate in ADR 0018); nothing accepting a credential ships beyond dev
without it. Not a code change — a deployment precondition, restated here because
it dominates every other item.

**C2 · MFA can be enrolled with only a stolen access token (ATO vector).**
`POST /v1/auth/mfa/totp/enroll` + `/activate` require only a valid bearer. An
attacker holding a leaked access token (e.g. via XSS) can enrol *their own*
authenticator and activate it, planting a second factor the victim does not
control. Industry practice (Google, GitHub) re-asks for the password before any
MFA change. *Fix:* require the account password on enrol and disable (step-up).
Designed below; not yet implemented — it changes the MFA request bodies, so it
wants your sign-off on the shape.

**C3 · Action tokens are replayable within their TTL (not single-use).** The
`PASSWORD_CHANGE_REQUIRED` and `MFA_REQUIRED` action tokens are verified but
never consumed. A leaked `202` response body lets an attacker set the password
(password-change token needs no old password) or complete MFA, any number of
times inside the 5-minute window. *Fix:* a one-time-use store keyed by the
token's `jti`, checked-and-burned on use. Needs a small table + a denylist
check; not yet implemented.

---

## High

**H1 · The service-token endpoint has no rate limiting.**
`POST /v1/auth/token` (client-credentials) verifies the secret with no throttle
(`ServiceClients.token`), so a client secret can be brute-forced offline-speed
over the network. Secrets are high-entropy today, which mitigates it, but a bank
throttles this path regardless. *Fix:* per-`client_id`+source throttle. Note the
current throttle table is tenant-scoped and service tokens are tenantless, so
this needs a small dedicated counter rather than reusing `login_throttle`.

**H2 · The breached-password check is a 10-entry list.** `Passwords.BREACHED`
holds ten strings. For a bank this is effectively no breach check. *Fix:* bundle
a real list (e.g. top-100k) or a k-anonymity range check against an offline
dataset; keep it self-contained (no external call at login).

**H3 · Edge CORS reflects any origin.** `Access-Control-Allow-Origin
$http_origin` echoes whatever Origin is sent. It is dev-shaped, but on the auth
API it should be an allowlist of the tenant's known web origins (the manifest
already carries `webOrigin`). *Fix:* pin CORS to configured origins; never
reflect.

**H4 · Verifier pins RS256 only as defense-in-depth (now fixed).** `LocalVerifier`
relied on `RSASSAVerifier` to reject a non-RSA algorithm. Added an explicit
`alg == RS256` allowlist so alg-substitution (`none`, or HS256-with-public-key)
is refused at the door. Services verifying via `libs/auth` already default to
RS256-only through `NimbusJwtDecoder`. **Done this pass.**

**H5 · No idle/inactivity session timeout.** Refresh families carry only an
absolute expiry (12 h). A bank usually also enforces an idle window (e.g. 15 min
of inactivity ends the session). *Fix:* stamp `last_used_at` on rotation and
refuse a refresh older than the idle window.

**H6 · Refresh rotation is not bound to the presenting client.** The family
records `client_id`, but `rotate()` does not check that the caller is the same
client. A refresh token stolen from a web client could be redeemed by a
different client id. *Fix:* verify the rotation's `clientId` matches the family.

---

## Medium

**M1 · Tenantless audit rows are visible in every tenant.** `auth_events` RLS is
`USING (tenant_id = current_tenant() OR tenant_id IS NULL)`, so service-token
events (which are tenantless) show up in any tenant's audit query. Low
sensitivity, but a tenant seeing platform-level events is a boundary smell.
*Fix:* route the audit-query read through a policy that hides `tenant_id IS
NULL` from tenant callers, or give service events a platform-reserved tenant.

**M2 · Lockout is evaluated after the password hash.** A locked account still
pays a full Argon2 verification on every attempt, so a locked account is a
hash-DoS amplifier. The per-source throttle limits it, and checking lockout
before the hash would leak "this account is locked" via timing — a real
trade-off. *Fix (optional):* a cheap per-source pre-gate already exists; consider
an account pre-gate with a constant-time floor.

**M3 · The full API spec is served publicly.** `/docs` and `/openapi.yaml`
expose the entire surface, admin endpoints included. It is open-source anyway, so
this is disclosure-of-shape only. *Fix:* gate `/docs` behind auth in production,
or serve only the authentication tag publicly.

**M4 · No password-age / forced-rotation policy.** There is length + reuse +
breach, but no maximum age. Some bank regimes require periodic rotation. *Fix:*
optional per-tenant `maxAgeDays`, enforced at login as a soft `ACTION_REQUIRED`.

**M5 · No recovery-code regeneration endpoint.** `enroll` silently replaces the
set; there is no "I used most of my codes, give me new ones" flow. *Fix:* a
`POST /v1/auth/mfa/recovery/regenerate` (step-up gated).

**M6 · Service-client secrets are stored as plain SHA-256.** Fine for
high-entropy generated secrets, weak if any deployment sets a guessable one.
*Fix:* document the entropy requirement, or hash with Argon2 like passwords.

---

## Missing APIs a core bank needs (specified, not yet built)

All are in `openapi.yaml` as `planned`; this is the honest "what a bank cannot do
yet" list. The first two block a *usable* institution the most.

1. **Staff administration (directory).** No API creates a second user, assigns or
   changes roles/units, deactivates, unlocks, admin-resets a password, or lists
   and revokes a user's sessions. Today a tenant has exactly the one seeded
   super-admin. This is the biggest functional gap (drives, and is driven by,
   Core's `admin-surface.md` §5). **Maker-checked** per PRD §4.10.
2. **Roles & permissions.** No permission-catalog endpoint, no role
   create/edit/delete (ADR 0017). Without it the eight `job:*` templates are the
   only roles a tenant has.
3. **Per-tenant policy.** No get/set for password rules, MFA-required-per-role,
   session TTLs, or the **admin IP allowlist** — which PRD §4.10 names and which
   is not enforced anywhere today.
4. **Audit query.** The trail is written but cannot be read through an API.
5. **Password reset (self-service).** Request/confirm — *delivery-gated* on the
   messaging connector. Admin-initiated reset (also unbuilt, item 1) is the
   interim.
6. **Email & phone verification.** *Delivery-gated.* No proof a contact address
   is real — which KYC and notification both eventually need.
7. **Self-service sessions/devices.** List my sessions, revoke one, see last-seen
   — beyond the built `revoke-all`.
8. **OIDC discovery** (`/.well-known/openid-configuration`) — small, helps any
   standard verifier self-configure.
9. **Customer identity.** No customer-scoped tokens (a separate epic; the
   platform has no customer principal yet — see the client-readiness analysis).

---

## What is genuinely solid (verified this pass)

- **Token contract** is frozen and proven by `TokenParityTest`; service tokens
  carry no tenant claim (ledger's rule) and action tokens are inert elsewhere.
- **Refresh rotation** detects theft: reuse of a rotated token revokes the whole
  family and audits it (`Sessions.rotate`, `FOR UPDATE OF t,f` serializes the
  race).
- **Uniform refusal**: one `AUTH_FAILED`, decoy-hash timing for unknown users,
  silent lockout that a correct password cannot distinguish from a wrong one.
- **RLS** enabled + FORCEd on every tenant-scoped table; `SET LOCAL` context.
- **Secrets**: passwords Argon2id; TOTP secret AES-GCM at rest; recovery codes
  and refresh tokens stored only as digests; no signing-key table by design.
- **TOTP** is RFC 6238, checked against the RFC's own vectors.

---

## Recommended remediation order

1. **C1** (TLS gate) — deployment, not code, but first.
2. **C2, C3** (MFA step-up, single-use action tokens) — the two real ATO/replay
   vectors. Small, high-value; both change a request body or add a table.
3. **H1, H5, H6** (service-token throttle, idle timeout, client-binding) — cheap
   session hardening.
4. **H2, H3** (breach list, CORS allowlist) — data + config.
5. **Missing #1–#3** (directory, roles, policy) — the functional path to a bank
   that more than one person can run. Largest effort; co-designed with Core's
   admin-surface.
6. Everything else as capacity allows.

Nothing here blocks the branch from merging as the *foundation*; these are the
named next steps to make it bank-grade, stated plainly rather than discovered
later.
