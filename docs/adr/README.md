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
| 0006 | Customer, Product and Orchestration ship as one Core deployable | Accepted — amended by 0020 |
| 0007 | Tenant isolation is a platform pattern, not a per-service invention | Accepted |
| 0008 | One event envelope for the whole platform | Accepted |
| 0009 | Authenticated service callers; the ledger enforces its own allowlist | Accepted |
| 0010 | One Keycloak realm per tenant, and identity lands before Core | **Superseded by 0018** |
| 0011 | The platform's first event consumer is built before Phase 3 | Accepted |
| 0012 | Organizational model: unit is operational scope, tenant is the legal entity | Accepted |
| 0013 | Lending is the first module built after the money path | Withdrawn |
| 0014 | The edge is configuration, reads come through Core, identity gets real first | Accepted |
| 0015 | The control plane is a deployable of its own, and provisioning is a saga | Deferred |
| 0016 | Tenants are declared in a manifest and seeded at startup | Accepted — implemented |
| 0017 | Permissions are platform vocabulary; roles are tenant-composed | Accepted — implemented |
| 0018 | Identity is a first-party service; Keycloak is retired | Accepted — implemented, supersedes 0010 |
| 0019 | A service that reads tenant data holds a tenant-scoped service principal | Accepted — implemented |
| 0020 | Customer and Product become deployables of their own | Accepted — implemented, amends 0006 |
