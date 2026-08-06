# Architecture Decision Records

Every significant technical decision is recorded here — the PRD is the guide,
ADRs are the individual choices. Format: context → decision → consequences.
Numbered, never deleted; superseded ADRs are marked as such.

| # | Decision | Status |
|---|---|---|
| 0001 | Java 25 LTS + Spring Boot for core services | Accepted |
| 0002 | Maven multi-module monorepo | Accepted |
| 0003 | AGPL-3.0-only + CLA, open from day one | Accepted |
| 0004 | Ledger Service is built first | Accepted |
| 0005 | Broker-agnostic event backbone, Kafka recommended | Accepted |
| 0006 | Customer, Product and Orchestration ship as one Core deployable | Accepted |
| 0007 | Tenant isolation is a platform pattern, not a per-service invention | Accepted |
| 0008 | One event envelope for the whole platform | Accepted |
| 0009 | Authenticated service callers; the ledger enforces its own allowlist | Accepted |
| 0010 | One Keycloak realm per tenant, and identity lands before Core | Accepted |
| 0011 | The platform's first event consumer is built before Phase 3 | Accepted |
