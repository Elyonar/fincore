# Identity — Threat Model

**Status:** DRAFT v0.1 (2026-08-09)

The service that makes this document necessary is the one service where a bug
is a breach. Each row names the threat, the control, and where the control is
proven. A control without a named suite is a claim, not a control — those rows
say PLANNED loudly.

| Threat | Control | Proven by |
|---|---|---|
| **Account enumeration** — login, reset, throttle responses reveal which usernames exist | One `AUTH_FAILED` shape for every credential failure; throttling applies identically to nonexistent accounts; directory errors only on the authenticated service surface | Adversarial suite: response-shape equality across causes (PLANNED) |
| **Credential stuffing / brute force** | Per-account and per-source rowed throttling, progressive delay, temporary lock; alarmed metric on failure rate and lockouts | Throttle suite incl. multi-instance agreement via rows (PLANNED) |
| **Timing oracles** | Hash verification runs against a real stored-cost digest even for unknown users (a fixed decoy hash); comparisons constant-time via the crypto library | Statistical timing test, tolerance documented (PLANNED) |
| **Refresh-token theft** | Opaque 256-bit values, digest-at-rest, family rotation; reuse of a rotated token revokes the family and audits; families revoked on password change and admin reset | Rotation suite: reuse → family dead, both tokens refused (PLANNED) |
| **Access-token theft** | 5–10 min lifetime bounds exposure; revoke-all kills refresh so theft does not renew; `jti` audited | Lifetime + revoke-all suite (PLANNED) |
| **Signing-key compromise** | Keys never in the database or logs, supplied by reference (D13); `kid` rotation with bounded overlap; key age alarmed | Rotation-overlap suite: old-key tokens verify until expiry, then fail (PLANNED) |
| **Database compromise** | Argon2id hashes; refresh and client secrets digested; no signing keys present; hash material segregated from directory reads | Schema-enforcement suite + grants review (PLANNED) |
| **Cross-tenant access** | Full ADR 0007 pattern despite single-institution instances; instance-to-instance isolation is key isolation — a foreign instance's token fails signature verification, tested explicitly | RLS bleed tests + foreign-issuer probe (PLANNED) |
| **Privilege escalation via grants** | Permission strings validated against the platform catalog on write; grantor rule (no granting what you do not hold) enforced in the directory as well as Core; role/user changes maker-checked in Core; last-administrator removal refused under serialization | Grant suite incl. the concurrent last-admin race (PLANNED) |
| **Forced-change bypass** | A temporary credential never yields an access token; the action grant is single-purpose, short-lived, and consumed on use | Action-grant suite (PLANNED) |
| **Malicious or compromised service caller** | Directory surface requires a verified service credential *and* forwards the initiating identity; both attributed; the ledger's azp-allowlist pattern reused | Directory auth suite (PLANNED) |
| **Replay of creation requests** | Idempotency by unique index on the natural key; same key + different payload is a loud conflict | Existing platform idempotency pattern, copied with its tests (PLANNED) |
| **Transport interception** | Out of this service's hands and stated anyway: TLS at the edge is a precondition of any non-development exposure (ADR 0018 consequence). This service refuses to pretend otherwise. | Deployment gate, not a test |
| **Log leakage** | Credentials, tokens and digests never logged; the audit table stores event facts, not secrets | Log-scrub review + audit-content test (PLANNED) |

## Assurance posture

Three legs, none optional: this document reviewed with the design; the
adversarial suites above implemented before the design is marked AGREED-built
(testing.md tracks each); **independent security review before any
non-development deployment**. The OWASP ASVS V2/V3/V7 checklists are the
review's working document, mapped row-by-row to the suites above.
