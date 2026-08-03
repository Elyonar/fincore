# libs/

Shared internal libraries. Empty by design — a library is extracted here only
when a second service needs it, never speculatively.

Planned residents (per PRD):

- `libs/auth` — the shared authorization library (§6.3): token validation,
  identity/tenant extraction, `require(permission)` helpers. Arrives with the
  second service.
- `libs/events` — event contracts (names, payload schemas, envelope) for the
  platform event catalog. Arrives when the first consumer exists.

Rule: services may depend on libs; libs may never depend on services; services
may never depend on other services' internals — only on their APIs and events.
