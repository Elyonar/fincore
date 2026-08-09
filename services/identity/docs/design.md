# Identity — Design

**Status:** DRAFT v0.1 (2026-08-09) — no domain code lands while DRAFT.
Recorded by [ADR 0018](../../../docs/adr/0018-first-party-identity-service.md).

The platform's identity provider as a first-party deployable: it verifies
credentials, mints the tokens every other service already verifies, owns the
staff directory and per-tenant authentication policy, and retires Keycloak.

Doc set: [`api.md`](api.md) · [`data-model.md`](data-model.md) ·
[`threat-model.md`](threat-model.md) · [`testing.md`](testing.md)

---

## 1. What this service is, in one paragraph

A caller presents a credential; the service answers with a short-lived signed
token and a rotating refresh grant, or with one uniform refusal. Every other
service keeps verifying tokens locally against this service's published keys —
which preserves the platform's availability posture (PRD §6.1): if Identity is
briefly down, issued tokens keep working and money keeps moving; only new
logins block. The service additionally serves a service-facing directory API
that Core's administration surface (admin-surface.md §5) drives; tenants never
address the directory directly, exactly as they were never to see Keycloak.

## 2. Boundaries — never in this service

- **No authorization decisions.** Deny-by-default enforcement stays in the
  owning services via `libs/auth`. This service *states* what a caller holds;
  it never decides what a holding permits.
- **No product surface for administration.** User, role and unit management as
  a product belongs to Core (admin-surface.md §5, AGREED). This service is the
  directory those endpoints drive.
- **No permission vocabulary authorship.** The catalog is platform code
  (ADR 0017); this service consumes it as the list of grantable strings and
  refuses grants outside it.
- **No customer credentials in v1.** Customer-facing identity is a separate
  epic with its own store and its own claims; it is enabled by owning the
  issuer but deliberately not started here.
- **No OIDC authorization-code provider.** There is no `/authorize`, no
  consent, no redirect flow — the platform's clients are first-party and
  client-driven by product decision (ADR 0018). This service is a token issuer
  for this platform, not a general-purpose IdP.
- **No money, no ledger access, no event consumption.**

## 3. Decision log

| # | Decision | Resolution · rationale |
|---|---|---|
| D1 | Authentication is a first-party API | Product constraint recorded in ADR 0018. Clients POST credentials to this platform; no hosted UI, no redirect. |
| D2 | The token contract is frozen at today's shape | RS256, JWKS, `tenant_id` / `preferred_username` / `permissions` / `units` / `jti`; `azp` on service credentials, which carry no tenant claim. `libs/auth` and the ledger's caller rules are the acceptance boundary and do not change. |
| D3 | Access tokens are short-lived; refresh is where sessions live | Access 5–10 min (per-tenant policy, PRD §4.10's stated band). Revocation therefore acts within minutes without per-request introspection, keeping verification local. |
| D4 | Refresh tokens are opaque, hashed at rest, and rotate in families | 256-bit random values, stored as digests, grouped per login into a family. Every use rotates; **use of an already-rotated token revokes the entire family** and writes an audit event — theft detection, not just expiry. |
| D5 | One uniform credential refusal | Unknown user, wrong password, disabled and locked answer identically in code, shape and observable timing. Deliberate deviation from the error contract's `reason` rule, recorded in `api.md`: distinguishing causes is an oracle. |
| D6 | Argon2id for password hashing | Via the vetted crypto library already on the classpath; parameters versioned in the stored hash; re-hash transparently at next successful login when parameters change. No bespoke crypto anywhere in this service — composition over invention (ADR 0018). |
| D7 | Temporary credentials force a change before any token is minted | Preserves ADR 0016's first-administrator arrival as an explicit API state: login with a temporary credential yields a single-purpose action grant whose only power is setting a new password. |
| D8 | Service credentials are a first-class flow | A client-credentials analogue mints the service token the ledger already expects (`azp`, no tenant claim). Closes the standing residual where Core's service token is deployment-supplied static configuration. |
| D9 | Full tenancy pattern despite one-instance-one-institution | scaffold §5 applies whole: `tenant_id` on every row, RLS enabled and FORCEd, `SET LOCAL` context, registry seeded from the manifest. Defense in depth, and the multi-tenant capability is preserved rather than amputated. |
| D10 | Plain SQL over JDBC, no ORM | Same reasoning as the ledger: small schema, security-sensitive writes, and the interesting behaviour (unique-index arbitration, RLS, row locks on lockout counters) is PostgreSQL's. Recorded per scaffold §3. |
| D11 | Every authentication event is recorded, append-only | Success, failure, lock, unlock, rotation, reuse-revocation, password change, directory mutation: one audit table, insert-only, dual-attributed where a service acted for a human. This is the auth audit trail PRD §4.10 names. |
| D12 | No events published in v1 — stated omission | Nothing consumes them yet; the audit table serves the need. An outbox arrives with the first real consumer, per the standing rule against anticipatory machinery. |
| D13 | Signing keys are deployment-supplied and never stored in the database | Referenced by the same `*Ref` indirection the manifest already uses; each key carries a `kid`; rotation keeps the outgoing key published for verification until every token signed by it has expired. A database compromise must not yield the signing key. |
| D14 | MFA (TOTP) and step-up are phase 2, additive | Designed as an `ACTION_REQUIRED` step in the same login flow plus `auth_time`/`acr` claims; nothing in phase 1 has to change shape to admit it. Not in the swap slice. |
| D15 | The service-facing directory API verifies callers with `libs/auth` against this service's own issuer | The public authentication endpoints are necessarily pre-identity and are the service's open paths; everything else denies by default like every other service. |
| D16 | Login throttling and lockout are per-account and per-source, silent | Progressive delay, then a temporary lock that never changes the refusal shape (D5). Unlock by expiry or by the admin surface. Counters live in rows, not memory, so multiple instances agree. |

## 4. Token shapes

**Staff token** (verified by `libs/auth`, unchanged): `iss` (this deployment's
issuer), `sub` (directory user id), `preferred_username`, `tenant_id`,
`permissions` (flat effective set: the user's roles resolved against the
catalog at mint time), `units`, `jti`, `iat`/`exp`, `azp` (requesting client).
Phase 2 reserves `auth_time` and `acr`.

**Service token** (verified by the ledger's existing rules, unchanged): `iss`,
`sub` = `azp` = client id, `jti`, `iat`/`exp`, **no tenant claim**.

**Refresh token**: opaque, never a JWT, meaningless off-instance.

The permissions claim is resolved at mint time, which makes the
revocation-window property admin-surface.md documents exactly true here: a role
change takes effect on the next minted token, bounded by the access-token
lifetime.

## 5. Bootstrap

A fourth `TenantSeeder` in the ADR 0016 shape: reads `bootstrap/tenants.json`,
validates the whole manifest before writing anything, refuses loudly on a
malformed entry, seeds idempotently and additively — the tenant row and exactly
one super-administrator with `job:admin`'s successor role, a temporary
credential generated at seed time and surfaced once, never committed. Removal
of an entry deprovisions nothing.

## 6. Operational posture

- Startup summary names the active mode and warns loudly for anything
  development-only, per scaffold §10.
- Stated alarms, each with a metric behind it: failed-login rate per tenant,
  lockouts per hour, reuse-revocations (each one is a suspected theft), token
  issuance failures, key age approaching rotation.
- Health/readiness; the JWKS endpoint is an open path and must be served before
  readiness reports true, because every other service's startup depends on it.

## 7. The swap slice (implementation gate, in order)

1. Scaffold per checklist: migrations, roles, RLS, presence/enforcement suites.
2. D2 token minting + JWKS, proven by a parity test: a token minted here
   resolves through `libs/auth` to an identical `IdentityContext` as the
   realm-minted golden token.
3. Login, forced change, refresh rotation, logout, uniform refusal, lockout.
4. Manifest seeding (D9, §5).
5. Service credentials (D8) and the compose swap: Keycloak service, realm
   template, import directory and `render-realms.sh` deleted in the same
   change. No period with two sanctioned issuers.
6. Directory API rows arrive with admin-surface §5's implementation, not
   before.

## 8. Honest limits at DRAFT

No MFA, no password reset by delivered message (notification delivers nowhere
yet; admin-initiated reset covers it), no customer identity, no federation. An
independent security review gates any non-development deployment — recorded in
ADR 0018 as a consequence, repeated here so this document cannot be read
without it.
