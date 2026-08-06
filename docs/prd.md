# Core Banking Platform — Product Requirements Document (PRD)

**Version:** 1.8
**Status:** Foundational ("Sacred Guide")
**Date:** August 2026 — v1.1: Onboarding & Migration; v1.2: Security & Identity, Keycloak; v1.3: Licensing; v1.4: roadmap audit (Phase 0, country packs, currency schema, BSL+dual licensing, content, revenue stack, GTM risks); v1.5: AI architecture designed-in (constitution #12, §3.3, AI Service §4.12); **v1.6:** licensing strategy replaced — open source (AGPL-3.0 + CLA) from day one per [ADR 0003](adr/0003-agpl-cla-open-from-day-one.md); §11 rewritten; **v1.7:** commercial model (§12) and risk register (§14) moved to an internal strategy document — this public edition covers vision, architecture, and product; **v1.8:** domain/deployable vocabulary made explicit (new §3.4, constitution #4 refined, §4 and §5 framed as domain decomposition rather than deployment topology) so that [ADR 0006](adr/0006-modular-core.md) and this document say the same thing
**Audience:** Founding team, senior backend & infrastructure engineers, collaborators

> **How to propose changes:** this document is the platform's source of truth,
> maintained in-repo so collaboration happens here. Changes come as pull
> requests that bump the version line and summarize the change in the header
> history. Accepted ADRs (`docs/adr/`) supplement it for individual technical
> choices — where an accepted ADR conflicts with an older PRD section, the ADR
> wins until the PRD is revised.

---

## 1. Vision & Positioning

Build a modern, cloud-native, API-first core banking platform for Africa, starting with Nigeria. We are a **pure software vendor**: our customers (microfinance banks, payment service banks, cooperatives, fintechs, and eventually commercial banks) hold their own CBN licenses. Money always moves under the customer's license and banking relationships — never through us. This keeps us unregulated, fast, and non-threatening to buyers.

**One-line pitch:** "You have the license. We have the technology. Live in weeks, priced in naira, CBN reports built in."

### 1.1 Market rationale (research-backed)

- Nigeria's major banks are in a core-banking migration wave costing tens of billions of naira annually, driven partly by FX-denominated license costs of foreign vendors (Finacle, Flexcube, Temenos).
- A locally built core (SeaBaaS/Peerless powering Sterling Bank) has validated the "Nigerian-built core" category: 2B+ transactions in year one.
- The CBN revoked 179 MFB licenses in 2023 and 46 more effective July 2026 — largely for operational and compliance failures. Affordable, compliance-ready core software is survival infrastructure for the remaining ~800 MFBs.
- Global vendors (Mambu, Oradian, Temenos) underserve small/mid MFBs, price in dollars, and lack deep USSD/agent/offline support. Local BaaS players (Anchor, Bloc) serve fintechs embedding finance, not licensed institutions needing a core.

### 1.2 Competitive advantages (in order of defensibility)

1. **Localization depth:** NIBSS/NIP, BVN/NIN, USSD, agent banking, CBN/NDIC reporting built in, working day one.
2. **Speed to launch:** signup → live in weeks, not 12–18 months.
3. **Naira-priced, transparent pricing:** published tiers; the hedge against FX pain.
4. **Self-service configuration:** product/fee/interest changes without vendor consultants.
5. **AI-native operations:** natural-language reporting, explained AML alerts, reconciliation exception detection — first in this market.

### 1.3 Non-goals (v1)

- Holding any CBN license or touching customer funds.
- Serving tier-1 commercial banks (later, via track record).
- Multi-currency *transactions* (FX legs, cross-currency products) — out of v1 scope. Currency-aware schema is **in** v1 scope (see §3.1): retrofitting currency into a ledger is prohibitive; carrying it from day one is cheap.
- Card issuing/processing in-house (connector to processors only).

---

## 2. Target Customers & Personas

| Segment | Profile | What they buy |
|---|---|---|
| Unit/State MFBs | 100–400k customers, thin IT teams, compliance-stressed | Full core + reporting + USSD/agents |
| Cooperatives & Ajo/Esusu groups | Group savings/lending, often manual | Simple core + group accounts + agents |
| Payment Service Banks | Rural reach mandate, agent-heavy | Core + agent module + NIBSS connector |
| Fintechs with licenses | Digital-first lenders/neobanks | API-first core + lending + webhooks |
| Integrators/resellers (later) | Regional SIs deploying for their FI clients | Commercial license + partner program (country-expansion channel) |

**Personas:** MFB Managing Director (buyer), Head of Operations (daily admin user), Head of IT (integrator), Compliance Officer (reports/AML user), Branch Teller, Field Agent, Fintech Developer (API consumer).

---

## 3. Architecture Principles (the constitution)

1. **The ledger owns truth.** No service writes balances except the Ledger Service. All channels are message deliverers into one posting engine.
2. **Double-entry, immutable, idempotent.** Corrections via reversing entries only. Every posting carries a client idempotency key.
3. **Customers' money moves under customers' licenses.** Connectors run on per-tenant credentials (their NIBSS keys, their telco shortcode, their card processor agreement).
4. **A deployable owns its database. No shared databases, ever.** Within a deployable, a module owns a *schema* and is reached only through its interface — never another module's tables. Vocabulary and the rules that follow: §3.4.
5. **Event-driven backbone.** Services publish domain events; consumers (notifications, compliance, reporting) subscribe. Synchronous calls only where an immediate answer is required.
6. **Configuration over code.** Products, fees, interest, limits, USSD menus, reports = tenant configuration, not deployments.
7. **Multi-tenant by default,** with tenant ID enforced at the data layer; dedicated-instance option for customers demanding isolation.
8. **Nigeria-aware cloud:** data residency in Nigeria (local/hybrid cloud), offline-tolerant edge apps.
9. **Real-time, no batch windows.** End-of-day is a verification pass, not a processing window. Zero scheduled downtime.
10. **Security by default:** RBAC + maker-checker on every sensitive action, encryption at rest and in transit, tamper-evident audit logs.
11. **Architect for Africa, build for Nigeria, expand one country at a time.** Nothing national is hardcoded in core services; country specifics live in connectors, report packs, and configuration (§3.1).
12. **AI advises, humans decide, the ledger obeys only deterministic rules.** AI never posts entries, computes balances, or autonomously approves transactions. All AI outputs are suggestions entering existing maker-checker flows, fully logged and attributable (§3.3, §4.12).

### 3.1 Internationalization & Country Packs

The platform is country-agnostic at the core; each market is a **country pack**:

| Country pack component | Nigeria (first pack) | Example: Kenya |
|---|---|---|
| Payment rails connector | NIBSS/NIP | Pesalink/PesaLink + M-Pesa |
| Identity/KYC connector | BVN/NIN | National ID/IPRS |
| Regulatory report pack | CBN + NDIC returns | CBK returns |
| KYC tiering config | CBN 3-tier | CBK tiers |
| Locale | en-NG, ₦ formats | en-KE/sw-KE, KES |
| Residency deployment | Nigeria region | Kenya region |

**Rules:**
- **Currency from day one:** every entry (not just account) carries a currency code; amounts stored as integer minor units (kobo/cents) — never floats; ledger invariants enforced **per currency**; cross-currency movement (post-v1) modeled as two single-currency legs through an FX position account, never a direct cross-currency entry.
- KYC tiering is a configurable framework; CBN's 3-tier model is its first configuration, not hardcoded logic.
- Regulatory reporting is a framework + versioned country packs (see §4.8).
- New country cost target: one rails connector + one KYC connector + one report pack + configuration — weeks with a local partner, not a rewrite. Country packs are prime integrator/reseller territory (§11).
- Most tenants are single-currency; the platform needs multi-currency *capability*, rarely per-tenant multi-currency *complexity*.

### 3.2 Technology Stack (recorded decision)

- **Language rule: monoglot core, earned-exception edges.** Java (Spring Boot) for all services holding domain logic (Ledger, Orchestration, Product, Lending, Customer, Compliance) — enterprise due-diligence credibility (JVM lineage of Finacle/Temenos/Mambu), ecosystem depth, Lagos talent pool. Locked in via [ADR 0001](adr/0001-java-lts-spring-boot.md) (Java 25 LTS).
- **Permitted exceptions (optional, not planned — use only if a concrete advantage materializes, each recorded as an ADR):**
  - *Go* — may be considered for peripheral, domain-logic-free services (connectors, USSD engine) where its concurrency model, footprint, and single-binary deploys offer clear operational benefit and team strength supports it. Default remains Java.
  - *Python* — may be considered for the AI Service only (§4.12), if and when its ML/LLM ecosystem (inference clients, eval harnesses, redaction tooling) proves materially faster to build with than JVM equivalents. Suggestive, not prescriptive — the AI Service can equally be Java.
  - *Rust* — no identified fit; complexity cost unjustified by any current bottleneck. Revisit only if we ever build extreme-throughput protocol processing in-house (not planned).
- Every additional language carries a standing tax (toolchains, duplicated shared libraries incl. auth/event/observability glue, review coverage, hiring) — exceptions must buy more than they cost.
- **Database:** PostgreSQL (per service); analytical read models for reporting.
- **Identity:** Keycloak, self-hosted (§4.10). **Event bus:** Kafka or equivalent. **Mesh/mTLS:** Istio/Linkerd when service count warrants (§6.2).
- **Stack principle:** adopt commodity (identity, broker, observability), build differentiation (ledger, product engine, channels, compliance).

### 3.3 AI Architecture (designed in from day one)

**The sacred boundary:** AI never touches the money path (constitution #12). The ledger stays deterministic, auditable, boring — which is precisely what makes AI *sellable* to regulators and boards: an AI that explains is an asset in a CBN examination; an AI that decides is a liability.

**Structural requirements (cheap now, prohibitive later):**
- **AI Service is its own microservice (§4.12):** a consumer of events and analytical read models; never a writer to the ledger; outputs are always suggestions routed into existing maker-checker flows.
- **Provenance & audit:** every AI suggestion logged — input, output, model/version, human accept/reject — in the same tamper-evident audit trail as everything else. AI actions are attributable to CBN examiners.
- **Residency-compatible inference:** configurable backends per tenant — hosted model APIs with PII-redaction layer for low-sensitivity tasks; self-hosted/in-region models for sensitive workloads. Tenants (especially banks) choose their posture. No tenant financial data flows to external model APIs without redaction and explicit tenant configuration.
- **Enabler already in place:** the event backbone + analytical store are the AI data foundation; no additional pipelines required.

**Placement map (feature → phase):**

| AI capability | What it does | Phase |
|---|---|---|
| Migration intelligence | Column-mapping suggestions, duplicate detection, name normalization on messy source data — human-confirmed | 2–3 (serves every deal) |
| Reconciliation triage | Suggested matches for exceptions with confidence + rationale | 3–4 |
| AML explanation & triage | Deterministic rules fire; AI drafts the "why" narrative, ranks the queue | 3–4 |
| NL analytics | "PAR>30 by branch this quarter" → visible generated query → chart | 4–5 |
| Configuration copilot | Plain-language product description → drafted config → maker-checker | 5 |
| Support copilot | Tenant-staff assistant on our docs; ticket deflection | 5 |

**Sequencing discipline:** AI is differentiator #5, not #1 (§1.2) — localization and compliance win deals; AI wins demos and press. AI work never steals engineering time from the ledger or the Nigeria layer. Migration intelligence comes first because it serves our own operational bottleneck.

### 3.4 Domains, deployables, and modules (vocabulary)

Three words that are easy to conflate and expensive to conflate. Most arguments
about "microservices versus monolith" are really arguments in which the two
sides are using one word for two different things.

- A **domain** is a bounded area of responsibility with its own data, rules, and
  language — Ledger, Customer, Product, Orchestration, Lending. §4 enumerates
  them. Domains are stable: they come from the business, not from the topology.
- A **deployable** is a process with its own database, its own release cycle, and
  its own network identity. This is what "service" means in constitution #4.
- A **module** is a domain living inside a deployable. It owns a **schema** in
  that deployable's database and is reached only through its interface.

The rules that follow:

- A deployable never reads another deployable's database. Integration between
  deployables is APIs and events, always.
- A module never reads another module's tables — not by join, not by shared
  repository, not "just this once". Integration between modules is the module's
  published interface.
- **Schema ownership is enforced by database privilege, not by convention:** each
  module connects as its own role, granted only on its own schema. A convention
  that only a reviewer enforces is a convention that erodes.
- One domain per module, always. A deployable may hold several modules; it may
  never hold half a domain, and a domain may never straddle two deployables.

Domains are permanent; packaging is a decision that is expected to change.
Moving a module into its own deployable is normal evolution, governed by
recorded extraction triggers rather than by architectural taste — see
[ADR 0006](adr/0006-modular-core.md).

---

## 4. Service Decomposition (domains)

This section decomposes the platform into **domains**: what each owns, what it
must do, what it publishes. It is deliberately silent on packaging. How many
processes these domains are deployed as is a different question, answered by §9
and by ADRs (§3.4, [ADR 0006](adr/0006-modular-core.md)). A domain described
here as a "Service" is not thereby promised its own process on day one — the
name describes the boundary of responsibility, not the boundary of deployment.

### 4.1 Ledger Service (the crown jewel)

**Owns:** chart of accounts, postings, balances, idempotency registry, invariant checks.

**Requirements:**
- Account types: customer, internal, fee, suspense, agent float, settlement mirror.
- Transactions of ≥2 entries; total debits must equal total credits or the whole transaction is rejected. Atomic commit.
- Append-only entries; reversals reference the original posting.
- Idempotency: same key → same result, never a double post; keys retained ≥ 7 years with the entries.
- Real-time balance per account, provable from entry history (balance = Σ entries).
- Concurrency safety: serializable isolation or row-level locking; simultaneous postings to one account never create or lose money.
- Invariant job: scheduled (≤ hourly) system-wide check that Σ debits = Σ credits and stored balances = derived balances; alert on any drift.
- Value dating and effective dating support for interest/backdated corrections (via reversing + new entries).
- Publishes events: `posting.completed`, `posting.reversed`, `account.created`, `account.closed`.

**Never in this service:** fees logic, product rules, external calls, orchestration. Keep it small, boring, rarely changed.

*Implementation home: [`services/ledger/`](../services/ledger/) — see its README and design docs.*

### 4.2 Customer Service

**Owns:** customer profiles, KYC documents & tier status, group structures, mandates/signatories.

**Requirements:**
- **Configurable KYC tiering framework** (per §3.1): tiers, their requirements, and their limits are country-pack configuration. First configuration: CBN 3-tier model — Tier 1 (minimal, phone-based, low limits) → Tier 3 (full KYC). Tier drives limits enforced at orchestration.
- Identity verification via pluggable country-pack connectors (Nigeria: BVN/NIN) — lifecycle: request → verified/failed → periodic revalidation; storing results not raw credentials where avoidable.
- Group/cooperative entities: members, roles, group accounts linkage, joint mandates (e.g., 2-of-3 signatories).
- Customer lifecycle: prospect → active → dormant → closed; dormancy rules configurable per tenant.
- NDPR compliance: consent records, data-subject export/erasure workflows (subject to financial record-retention law), field-level encryption for PII.
- Events: `customer.created`, `customer.kyc_tier_changed`, `customer.status_changed`.

### 4.3 Product & Pricing Service (the configuration engine)

**Owns:** product catalog, fee rules, interest rules, limit rules, per-tenant configuration.

**Requirements:**
- Product types v1: savings (incl. daily-contribution/ajo style), current, fixed/target deposit, loan products (see Lending).
- Declarative rule model: fees (flat/percentage/tiered/capped, per event type), interest (simple/compound, accrual frequency, day-count convention, posting frequency), limits (per KYC tier, per channel, per day/txn).
- Versioned configurations with effective dates; changes require maker-checker approval; full history retained.
- Self-service admin UI: an ops manager creates "Ajo daily savings, ₦50 minimum, 2% monthly interest, ₦20 withdrawal fee" in minutes without vendor involvement.
- Query API for orchestration: "what fees/limits/interest apply to account X for operation Y?" — answered in <50ms (cacheable, versioned).
- Events: `product.published`, `pricing.changed`.

### 4.4 Transaction Orchestration Service (the conductor)

**Owns:** transaction workflows (sagas), holds/authorizations, fee application, limit enforcement.

**Requirements:**
- Saga engine for multi-step flows: validate → AML pre-check → limit check → fee calc → ledger hold → external call (e.g., NIBSS) → confirm/compensate → final postings → events.
- Every step idempotent and compensatable; a NIBSS timeout releases holds deterministically; "unknown outcome" states park in a pending queue with automatic status re-query and, failing that, ops escalation — never silent loss, never double-post.
- Holds/authorizations as first-class: reserve funds pending external confirmation; TTL-based auto-release.
- Transfer types v1: intra-tenant (book transfer), inter-bank outbound/inbound via NIBSS connector, bulk disbursements (payroll/loan disbursement files).
- Standing orders / scheduled payments engine.
- All money movement across the platform flows through this service into the Ledger — channels never post directly.
- Events: `transfer.initiated/completed/failed/reversed`, `hold.placed/released`.

### 4.5 Lending Service

**Owns:** loan applications, schedules, disbursement instructions, repayments tracking, delinquency, restructuring.

**Requirements:**
- Origination workflow: application → configurable approval chain (maker-checker, amount-tiered) → offer → disbursement (via orchestration).
- Individual and **group lending** (Grameen-style joint liability): group schedule, member allocation, group guarantee logic.
- Schedule engine: equal installment (annuity), flat, bullet, custom; grace periods; moratoriums.
- Repayment allocation order configurable (penalties → interest → principal, etc.); partial payments handled deterministically.
- Penalty/late-fee rules from Product Service; delinquency buckets (1–30, 31–60, 61–90, 90+); automatic classification per CBN prudential guidelines.
- Restructuring/rescheduling with full audit history; write-off workflow with approvals.
- Portfolio analytics: PAR (portfolio at risk) by bucket/branch/officer/product, disbursement and collection reports.
- Credit bureau connector hooks (inquiry at origination, monthly reporting of performance).
- Events: `loan.approved/disbursed/repayment_received/delinquent/restructured/written_off`.

### 4.6 Connector Framework (one service per external system)

**Design:** each connector is an isolated service translating our internal protocol to the partner's, holding **per-tenant credentials** (encrypted vault), with its own retry/timeout/circuit-breaker policy and sandbox mode. A partner outage or API change never touches core code.

**v1 connectors (priority order):**
1. **NIBSS/NIP** — outbound transfers, inbound credit notifications, name enquiry, transaction status query (TSQ) for unknown outcomes.
2. **BVN/NIN verification** — via NIBSS/NIMC-approved channels.
3. **SMS gateway** — transactional alerts, OTPs (per-tenant sender IDs).
4. **USSD gateway** — telco session integration (tenant's shortcode).
5. **Credit bureaus** (CRC, FirstCentral) — inquiries + submissions.
6. **Card processors** (Interswitch/UP) — phase 2.

**Requirements common to all:** request/response logging (redacted), reconciliation feeds where partner provides them, health dashboards, per-tenant rate limiting.

### 4.7 Channels Services

**4.7.1 USSD Engine**
- Menu trees as per-tenant configuration (visual builder in admin UI).
- Session state store; ~120s telco timeout awareness; resumable where telco supports it.
- Every money action goes through Orchestration with an idempotency key derived from session+step — dropped sessions never half-complete.
- PIN management (hashed, attempt lockouts), per-tier limits enforced.
- Menu analytics (drop-off points) for tenants.

**4.7.2 Agent Service**
- Agent hierarchy: aggregator → super-agent → agent; territory tagging.
- **Float accounts live in the Ledger** (agent float is a ledger account type) — cash-in debits float/credits customer; cash-out reverses. All double-entry guarantees apply automatically.
- Commission engine: configurable per transaction type/tier; accrued and posted automatically; commission statements.
- Agent Android app + POS integration: onboarding, cash-in/out, balance, mini-statement, float top-up requests. App runs on phones and Android smart-POS terminals (same codebase); receipt printing via POS printer SDKs.
- **Terminal management:** device registration and binding (terminal ↔ agent), remote deactivation/kill of lost or stolen devices, device attestation on login (device + PIN authenticator), terminal inventory per tenant/aggregator.
- **Offline mode:** local queue of cryptographically signed, timestamped transactions; sync with idempotency keys on reconnect. Offline allowlist is configurable per tenant — default: cash-in queueable offline (cash physically in hand), withdrawals/transfers online-only or small-limit against agent float risk. Conflicts surface in an ops reconciliation queue; never silently overwritten.
- Fraud controls: velocity checks, limit tiers, geo-anomaly flags, instant agent suspension.

**4.7.3 API Gateway & Partner APIs**
- Single entry for tenant mobile apps, internet banking, and third parties the tenant authorizes.
- OAuth2/OIDC + API keys per consumer; per-consumer rate limits and scopes (tenant-controlled).
- Webhooks: signed, retried with backoff, replayable from dashboard.
- Developer experience: OpenAPI specs, sandbox tenant with test money, Stripe-quality docs, versioned APIs (v1 stable contract).
- Open-banking readiness: expose account/transaction APIs aligned with CBN open banking standards so tenant compliance is configuration, not a project.

**4.7.4 Teller/Branch Web App**
- Teller till as a ledger account; cash drawer open/close with denominations; till-to-vault movements.
- Deposits, withdrawals, transfers, account opening, statement printing.
- Offline-tolerant (same queue rules as agents), branch supervisor approvals.

### 4.8 Compliance & Reporting Service

**Owns:** regulatory report generation, AML monitoring, audit query interface, analytical event store.

**Requirements:**
- Consumes all domain events into an analytical store (read-optimized, separate from operational DBs).
- **Regulatory reporting = framework + country packs** (§3.1). First pack — Nigeria: CBN prudential returns for MFBs (capital adequacy inputs, liquidity, portfolio classification), rendition file formats as specified by CBN; NDIC returns. Report templates versioned per regulation updates; tenants generate on schedule or on demand. New jurisdictions ship as report packs, never core changes.
- **AML/CFT:** configurable rule engine (thresholds, structuring patterns, velocity, watchlist screening hooks); alert queue with case management (assign, investigate, resolve, SAR-ready export); every alert carries a plain-language explanation of why it fired (AI-assisted).
- Audit interface: any record's full history (who/what/when/before/after) queryable in seconds; exportable for CBN examinations.
- Tamper-evidence: append-only audit log with hash chaining.
- **AI reporting assistant:** natural-language queries over the tenant's own data ("show PAR>30 by branch this quarter") producing tables/charts with the underlying query visible.

### 4.9 Notification Service

- Template management per tenant (SMS/email/push), variables, multi-language (English v1; Hausa/Yoruba/Igbo templates supported as tenant content).
- Event-driven sends (posting completed → debit alert), quiet-hours and dedupe rules, delivery status tracking, cost reporting per tenant.

### 4.10 Identity & Access Service

**Build decision: implemented on Keycloak (open-source, self-hosted), not built from scratch.** Rationale: authentication is commodity software where correctness is proven by a decade of hostile exposure and continuous CVE patching, not by review; self-hosting satisfies Nigerian data residency; zero per-user license cost preserves naira pricing economics; realms map naturally to multi-tenancy; Java base matches our stack; Red Hat-backed pedigree passes bank due diligence. Everything downstream speaks standard OIDC, so the provider remains swappable (low lock-in).

**Scope of the service:**
- Staff, agents, and API consumers; OAuth2/OIDC provider issuing short-lived JWTs (5–15 min) + refresh tokens; JWTs carry user ID, tenant ID, roles/permissions, branch/agent scope, approval tier.
- **Centralized definitions:** the master permission vocabulary, role templates per segment (Teller, Agent, Compliance Officer, MFB Admin, etc.), and role assignments live here. Enforcement is decentralized to owning services (see §6).
- RBAC with fine-grained permissions; **maker-checker** required on: product changes, limit overrides, reversals, agent suspension, report submission, user/role changes. Thresholds configurable. Identity defines who can make/check; owning services enforce the workflow.
- Tenant management: onboarding, feature flags, plan/entitlements, branding. Our tenant admin dashboard drives Keycloak via its admin API — tenants never see Keycloak directly.
- Session policies, MFA (TOTP/SMS), IP allowlists for admin, full auth audit trail; per-tenant password/MFA/session policies.

**Customization levels (in order of preference — never patch Keycloak source; forking forfeits the patch stream):**
1. *Configuration:* realms/tenancy model, policies, vocabulary and roles via admin API.
2. *Token shaping:* custom claim mappers (tenant ID, branch scope, approval tier).
3. *Wrapping:* our tenant admin dashboard + the shared authorization library (ours regardless of provider).
4. *SPI extensions (only where banking differs):* agent device+PIN authenticator, SMS OTP via our Nigerian gateway connector, enterprise user federation — sanctioned plugins that survive upgrades.

**Build sequencing:** Identity is *set up* first (days: deploy, first realm, starter vocabulary, shared auth library) but the Ledger is *developed* first (months). Auth is woven into the Ledger from its first endpoint — retrofitting identity context is prohibitively costly.

### 4.11 Operations Tooling (cross-cutting product surface)

- End-of-day verification dashboard: invariant results, unposted queues, connector reconciliation status, sign-off workflow.
- Reconciliation module: match ledger vs partner statements (NIBSS settlement reports, SMS logs); exceptions queue with AI-suggested matches; nothing auto-adjusted without maker-checker.
- Backdated correction workflow (reversing entries + audit reason codes).
- Fee/interest accrual job monitors with replay tools.

### 4.12 AI Service

**Owns:** all model inference, suggestion generation, prompt/model management, AI provenance logs. Governed by constitution #12 and §3.3.

- **Consumer only:** reads events and analytical stores; **no write path to Ledger or Orchestration exists** — architecturally, not just by policy (no service credentials for those write APIs).
- Suggestion API: other services request suggestions (migration mappings, recon matches, AML narratives, NL-query translation, config drafts); responses carry confidence + rationale + provenance ID.
- All suggestions route into the requesting service's existing maker-checker/approval flows; acceptance/rejection reported back and logged.
- Configurable inference backends per tenant (§3.3): redacted hosted APIs or in-region/self-hosted models; PII-redaction layer in this service, not in callers.
- Model/prompt versioning; evaluation harness for suggestion quality per capability; per-tenant enablement flags (AI features are opt-in per tenant).
- Events: `ai.suggestion_issued`, `ai.suggestion_accepted`, `ai.suggestion_rejected`.

---

## 5. Service Communication Map

Read alongside §3.4: this map describes **domain** interactions, not transports.
Where two domains share a deployable, the interaction below is an in-process
interface call; where they do not, it is a network call. "Synchronous" describes
the *dependency* — the caller cannot proceed without an answer — which is true
either way and is the property that matters when reasoning about failure.

Under the current topology ([ADR 0006](adr/0006-modular-core.md)): Orchestration
→ Product and Orchestration → Customer are in-process interface calls;
Orchestration → Ledger and Orchestration → Connectors are network calls.

**Synchronous (REST/gRPC between deployables; interface calls within one):**
- Channels → Orchestration ("do this transfer")
- Orchestration → Ledger ("post/hold now"), → Product ("what fees/limits?"), → Connectors ("send to NIBSS")
- Services → AI Service ("suggest a match/mapping/narrative") — request/response, suggestions only
- Admin UIs → respective services

**Asynchronous (event bus — Kafka or equivalent):**
- Ledger/Orchestration/Lending publish domain events
- Compliance & Reporting, Notification, Analytics, AI Service consume
- Connector inbound events (NIBSS inward credit) → Orchestration
- AI Service publishes suggestion lifecycle events → audit/Compliance

**Data:** one database per **deployable**, one schema per **module** within it (§3.4); PostgreSQL default. Analytical store for Compliance/Reporting fed by events (Postgres read models v1; columnar store later). AI Service reads analytical stores, never operational databases of other services.

---

## 6. Security & Identity Architecture

Three layers: edge authentication, service-to-service authentication, and domain authorization. Governing principle: **definitions are centralized (Identity Service), enforcement is decentralized (each owning service).**

### 6.1 Layer 1 — Edge authentication (who is this human/app?)

- All external traffic enters via the API Gateway. Identity Service (Keycloak) is the OAuth2/OIDC provider.
- **Staff/tellers/agents:** password + MFA login → short-lived JWT access token (5–15 min) + refresh token. JWT claims: user ID, tenant ID, roles/permissions, branch/agent scope, approval tier.
- **Tenant end-customer apps:** same flow; tokens scoped to that customer's own accounts only.
- **API partners:** OAuth2 client-credentials; API key + secret → token with explicit scopes (e.g., `accounts:read`, `transfers:create`) granted and revocable by the tenant from their dashboard.
- Gateway validates JWT signature locally via published JWKS keys (no per-request call to Identity), checks expiry, applies per-consumer rate limits. Invalid requests never reach services.
- **Identity availability posture:** Identity is contacted only at login/refresh/admin changes. If Identity is briefly down, issued tokens keep working and money keeps moving; only new logins block. Critical infrastructure, not a per-transaction dependency.

### 6.2 Layer 2 — Service-to-service authentication

Internal services must not blindly trust each other; a compromised peripheral service must not be able to reach the ledger.

- **mTLS between all services** — mutual certificate verification and encryption; in Kubernetes, a service mesh (Istio/Linkerd) automates cert issuance/rotation.
- **Service identity tokens** — each service asserts its own machine identity. Allowlists per service: the **Ledger accepts posting requests only from Orchestration** (plus a read-only path for Reporting). Smallest possible trust surface around the crown jewel.

### 6.3 Layer 3 — Domain authorization

- Validated identity context propagates downstream (signed headers / propagated JWT).
- **Shared authorization library** (one internal package, imported by every service): validates token, extracts identity/tenant/permissions, provides `require(permission)` helpers. Mechanics written once.
- **Each owning service adds domain checks only it can know:** Lending checks approval tier vs amount, loan state, and maker ≠ checker; Product checks maker-checker state before publishing. The Gateway authenticates; it never makes business authorization decisions.
- **Tenant isolation enforced at the data layer,** not only in code: every query auto-filtered by token tenant ID, with Postgres row-level security as backstop — an application bug still cannot leak cross-tenant data.
- **Maker-checker as authorization-with-time:** sensitive actions persist as pending approvals; a distinct checker-role user approves before execution; both identities audited.

### 6.4 Request flow (reference: teller-initiated transfer)

1. Teller app → Gateway with JWT. Gateway validates, rate-limits, forwards with identity context.
2. Orchestration: role can initiate? amount within teller limit and customer KYC-tier limit (Product query)? → runs saga.
3. Orchestration → Ledger over mTLS with service token; Ledger verifies caller is Orchestration; commits posting.
4. Audit trail records **both** the initiating human and the executing service chain — the dual attribution CBN examiners expect.

### 6.5 Sacred rules

1. **Short-lived tokens everywhere.** A stolen 10-minute token is an incident; a month-long one is a breach.
2. **The Ledger trusts almost no one.** One writer (Orchestration); read-only for Reporting.
3. **Deny by default.** New endpoint/role/service = no access until explicitly granted; every grant is itself an audited maker-checker event.
4. **Never fork commodity security software.** Extend Keycloak via config, admin API, and SPIs only (§4.10).
5. **Adopt commodity, build differentiation.** Identity, broker, observability are configured scaffolding; engineering effort concentrates on ledger, product engine, channels, compliance.

---

## 7. Non-Functional Requirements

| Area | Requirement |
|---|---|
| Availability | 99.9% v1 target (≤ ~43 min/month), 99.95% by year 2; zero planned downtime windows |
| Ledger latency | Posting commit p99 < 200ms internal; end-to-end intra-tenant transfer p99 < 1s |
| Throughput | Design for 200 TPS sustained per tenant cluster v1; horizontal path to 2,000+ |
| Durability | RPO ≤ 5 min, RTO ≤ 1 hr v1; continuous WAL archiving; cross-zone replicas; quarterly restore drills |
| Data residency | Primary data in Nigeria-located infrastructure (local cloud/hybrid); document for CBN examiners |
| Security | Encryption in transit (TLS 1.2+) and at rest; secrets in vault; OWASP ASVS L2; annual pentest; ISO 27001 roadmap started year 1 |
| Privacy | NDPR: processor obligations, DPA templates for tenants, PII field encryption, retention schedules |
| Auditability | Every state change attributable and reconstructible for ≥ 7 years |
| Observability | Structured logs, metrics, distributed tracing across saga steps; per-tenant dashboards |

---

## 8. Testing & Correctness Strategy (non-negotiable)

1. **Invariant tests:** after every test scenario and hourly in production — Σ debits = Σ credits; stored balances = derived; no unauthorized negative balances.
2. **Property-based tests:** generated random operation sequences (transfers, fees, reversals, holds) must preserve invariants; shrinking on failure.
3. **Concurrency tests:** N threads hammering shared accounts; total money conserved across hundreds of runs in CI.
4. **Failure injection:** kill DB mid-saga, timeout connectors at each step, duplicate deliveries; assert complete-or-compensated, never partial.
5. **Reconciliation tests:** simulated partner (fake NIBSS) that lies/omits; recon must flag every planted mismatch.
6. **Offline-sync tests:** queued agent transactions with conflicts; assert idempotent replay and conflict surfacing.
7. **Shadow-mode deployment:** first live tenants run parallel to legacy; daily automated comparison reports before cutover.

CI gates: no merge to Ledger or Orchestration without invariant + property + concurrency suites green.

---

## 9. Build Phases

**Phase 0 (Months 1–2): Pilot build / demo MVP — the true first milestone.** Scope, ruthlessly cut: real Ledger with full test suite (never faked — "try to break it live" is the demo's centerpiece), Product configuration demo (create the pilot MFB's actual products live, in minutes), teller web screens (open account, deposit, withdraw, statement — pilot-branded), basic loan flow (application → approval → disbursement → schedule), one populated report preview (PAR / CBN return mock). **Explicitly excluded:** USSD, agents, offline, live NIBSS (show connector design only), multi-tenant hardening, Keycloak realms (basic login suffices), notifications, migration toolkit.
- **With a committed pilot MFB:** Phase 0 is their shadow-mode pilot build, shaped to their products from week one.
- **Without one yet:** Phase 0 is the conversion instrument — demo first, LOIs/pilot agreements gate the full build.
- Discipline: partner reactions route into these phases; Phase 0 never silently grows into an unplanned v1.

**Phase 1 (Months 1–3): Foundation.** Ledger (standalone from day one) and Core — Customer + Product + Orchestration as clean internal modules, recorded with its boundaries and extraction triggers in [ADR 0006](adr/0006-modular-core.md) — plus the API Gateway when the first external consumer exists (§4.7.3; edge TLS and token validation are gateway configuration and the shared authorization library, not a service to build). Full test strategy live in CI. Admin UI for products/customers. Internal demo tenant. (Phase 0 is the front half of this work.)

**Phase 2 (Months 2–5, parallel): Design partners.** Convert/sign 2–3 MFBs/cooperatives (pilot fee or signed agreement required — skin in the game; reference rights negotiated) shaping the roadmap; begin NIBSS partnership paperwork immediately (slowest dependency). Pilot shadow-mode in one branch from ~Month 3: double-entry by their tellers, daily comparison reports = live production evidence.

**Phase 3 (Months 4–8): Nigeria layer.** NIBSS (pilot's credentials), BVN/NIN, SMS connectors; Lending service extracted; Compliance & Reporting v1 (core CBN returns validated by pilot's compliance officer, AML rules, audit queries); Notification; Teller app hardening; migration toolkit forged on pilot's real data.

**Phase 4 (Months 6–12): Channels & go-live.** USSD engine, Agent service with offline sync + terminal management; pilot full cutover; case study; selling to MFBs #2–3 on the live reference.

**Phase 5 (Year 2): Scale.** Card connectors (POS card-acceptance via processors), credit bureaus, open-banking APIs, AI reporting assistant GA, ISO 27001 certification, dedicated-instance offering, second country pack (Ghana/Kenya) via integrator partner (§3.1, §11).

---

## 10. Customer Onboarding & Migration

Onboarding machinery is **product, not services work**. Two journeys run on the same platform: a fast self-service-leaning path for small institutions, and a staged enterprise path for large ones. The small-customer machine is the training ground for enterprise: every small migration sharpens the toolkit and generates the references that pass a big bank's due-diligence gate. Target 10–20 small institutions before pursuing the first large bank.

### 9.1 Journey A — Small MFB / Fintech (target: 4–6 weeks contract-to-live)

**Stage 1: Tenant provisioning (Week 1).**
- Tenant created: isolated data space, tenant branding, admin accounts (MD, Head of Ops, Head of IT, Compliance Officer).
- Sandbox environment issued immediately with test money — customers explore before configuration begins.
- Entitlements/feature flags set per contract (modules purchased, limits, plan tier).

**Stage 2: Configuration, not code (Weeks 1–2).**
- Customer's ops team, guided by our customer-success, recreates their real products in the Product engine: savings types, loan products, fees, interest, limits.
- **Starter packs:** preset configuration templates ("standard Unit MFB pack," "cooperative pack," "digital lender pack") so customers begin from a working baseline, not a blank page.
- Branches created as organizational units; teller tills provisioned as ledger accounts; staff roles and maker-checker chains assigned.
- USSD menus and notification templates configured where applicable.

**Stage 3: Data migration (Weeks 2–3) — the make-or-break step.**
- **Migration toolkit** (built once, used in every deal — invest heavily):
  - Customer/account importers (CSV/Excel) with validation reports (duplicates, missing KYC fields, malformed records) returned before load.
  - Opening balances posted as **migration transactions** against a single "migration equity" account per tenant — day-zero balances obey double-entry and are fully auditable.
  - Open loans recreated with original schedules, outstanding principal/interest, and delinquency status.
  - Dry-run mode: full import into sandbox first, always.
- **Balance verification report:** old-system totals vs our ledger totals, line-by-line and in aggregate, reconciled to the kobo and signed off by customer management before any cutover. No sign-off, no cutover — non-negotiable.

**Stage 4: Connectors & training (Weeks 3–4).**
- Customer supplies their own credentials/agreements (NIBSS, SMS sender ID, USSD shortcode); connectors activated per tenant and verified in sandbox then production-test mode.
- Role-based training: tellers, agents, ops, compliance. Design target: each role productive after **half a day** of training — if it takes longer, the UI is wrong, not the trainee.

**Stage 5: Shadow mode → cutover (Week 4+).**
- Parallel run: transactions processed in both old and new systems; automated **daily comparison reports** highlight discrepancies.
- Clean comparison streak (target ≥ 5 consecutive business days) → scheduled cutover; old system archived read-only.
- Two-week hypercare with heightened support SLAs, then steady state.

### 9.2 Journey B — Large / National Bank (target: 6–9 months, staged)

Same platform, same toolkit — the differences are process, scale, and risk ceremony.

**Stage 1: Due diligence (Months 1–2, pre-contract).**
- Their IT/risk/audit teams assess us: security posture and certifications (ISO 27001 roadmap/attainment), architecture review, data-residency evidence, DR/BCP test results, penetration test reports, reference calls with live customers.
- Deliverable we maintain permanently: a **due-diligence pack** (standard responses, architecture docs, certificates, audited uptime history) so each enterprise deal doesn't restart from zero.

**Stage 2: Solution design (Months 2–3).**
- Joint workshops mapping their product catalog, GL structure, approval workflows, and report formats into our configuration model; gaps triaged as configuration → roadmap → (never) core custom code.
- **Deployment model decision:** dedicated instance (isolated deployment) typically required at this tier — supported natively by the architecture. Data-migration scope, integration map to their surrounding systems (mobile app, treasury, HR), and cutover strategy agreed and signed.

**Stage 3: Migration rehearsals (Months 3–5).**
- At millions of customers, migration is rehearsed, not attempted: 3–4 full test migrations of production data into staging, each producing reconciliation reports and a timing profile, iterated until the run is boringly repeatable and fits the agreed cutover window (typically a weekend).
- Rehearsals double as performance validation at the customer's real data volumes.

**Stage 4: Pilot branches (Months 4–6). Never big-bang.**
- 5–10 pilot branches live first (or in shadow mode alongside the legacy core), operated for several weeks; issues fixed; playbook updated.
- Branch waves follow: 20 → 50 → 100+, each wave with its own verification gate. Staged cutover is the direct answer to the publicized big-bang migration outages at major Nigerian banks — and a core part of our pitch.

**Stage 5: Full cutover + hypercare (Month 6+).**
- Legacy system retained read-only for reference/audit.
- Hypercare period with named engineers on standby and daily status calls, tapering to standard enterprise support.

### 9.3 Onboarding Machinery — Product Requirements

| Component | Requirement |
|---|---|
| Tenant provisioning flow | Create tenant + admins + sandbox in < 1 hour, self-serve for internal team |
| Sandbox | Instant, test money, resettable, mirrors production feature set |
| Migration toolkit | Importers with validation reports; migration-equity posting pattern; loan recreation; dry-run mode; repeatable/scriptable for rehearsals |
| Balance verification report | Automated old-vs-new reconciliation to the kobo; management sign-off workflow |
| Configuration starter packs | Maintained preset templates per segment; versioned |
| Shadow-mode comparison | Automated daily discrepancy reports between parallel systems |
| Training materials | Role-based, half-day-per-role target; in-product guides |
| Due-diligence pack | Maintained standard enterprise assessment responses + evidence |
| Onboarding dashboard | Internal view of every tenant's journey stage, blockers, and time-in-stage |

### 9.4 Commercial Implications

- Small institutions: near-self-service onboarding with light CS support keeps cost-to-serve compatible with naira pricing.
- Enterprise: implementation fees fund a dedicated delivery team; pilots and staged waves de-risk both sides.
- Onboarding speed is a headline metric and marketing asset ("live in weeks") — see Success Metrics.

---

## 11. Licensing, Source Strategy & Content

**Decision ([ADR 0003](adr/0003-agpl-cla-open-from-day-one.md)): open source from day one — AGPL-3.0-only for the platform, Apache-2.0 for SDKs and client libraries, CLA from the first external contribution, commercial dual licensing as the revenue mechanism.** This supersedes the closed-until-stable / BSL-at-release posture of PRD v1.3–v1.5.

### 11.1 Rationale

- The project's two scarcest resources are **trust and distribution**; public code buys both from the first commit. "Audit the ledger yourself" is available immediately, not after a Month-9 gate.
- **Funding eligibility:** grant, DFI, and digital-public-goods programs generally require genuinely open (OSI-approved) licenses; BSL-class licenses fail those gates. Open-from-day-one keeps the DPG/grant funding path available alongside commercial revenue.
- The build-in-public strategy (§11.4) and the open repository are one motion, not two: the content launches *with* the code.

### 11.2 License model

- **Platform code: AGPL-3.0-only.** Deters free-riding cloud hosts (network use triggers source obligations); institutions whose lawyers reject AGPL buy a commercial license — that is the business model working, not a bug.
- **SDKs & client libraries: Apache-2.0.** Adoption over protection at the edges.
- **CLA mandatory from the first external contribution** (CLA-bot enforced): all contributor rights assigned to the company — dual licensing requires unified copyright ownership. Non-negotiable; retrofitting is impossible. See `CLA.md` at the repo root.
- Optional free tier (e.g., institutions under a size threshold) remains a commercial-policy decision (pricing), not a license decision.
- **Architecture obligation (unchanged):** repo/module boundaries kept clean; every service is independently packageable.

### 11.3 Commercial license profiles (revenue lines beyond hosting)

| Profile | Who | What the license grants |
|---|---|---|
| Self-hoster | FI whose policy requires own-infrastructure deployment (or rejects AGPL) | Production use for named entity, capped by accounts/branches; bundled SLA/support; audit rights |
| Integrator/reseller | Regional SIs deploying for their FI clients | Per-deployment rights + partner terms — the country-expansion channel (§3.1) |
| Embedder | Fintech embedding the ledger/core in their own product | Embedding rights, priced by scale |

Managed-hosting customers need no commercial license (we run the code); they hold SaaS subscriptions. AGPL compliance is available to anyone; commercial licenses buy freedom from its obligations plus support. Enforcement is passive by design: our buyers are institutions that must be audit-clean.

### 11.4 Content & build-in-public strategy

- Document the journey publicly from week one: design decisions, lessons, market education ("what CBN reporting actually requires," "why African cores need offline agents"). Goal: become the public authority in an empty niche; compound trust; attract talent, partners, DFIs, press.
- The open repository is the content's anchor: episodes and posts tie to tagged releases, ADRs, and the public test suites (the "try to break the ledger" invitation).
- Budget ceiling ~10–15% of team time; content is a flank, not the main assault — MFB boards are won by references, content wins fintechs/talent/ecosystem.
- **Never publish security-sensitive specifics** (auth flows of real deployments, fraud thresholds, infrastructure details): decisions and lessons, not blueprints. Open source code ≠ open operational details.

---

## 12. Commercial Model

fincore is commercially supported open source: managed hosting subscriptions,
commercial licenses for self-hosters/integrators/embedders (§11.3),
implementation services, and enterprise support. Detailed pricing and revenue
strategy are maintained in an internal strategy document — they are commercial
policy, not product architecture, and do not gate any technical contribution.

---

## 13. Success Metrics

| Metric | Target |
|---|---|
| Ledger invariant violations in production | 0, ever |
| Tenant onboarding (contract → live sandbox) | < 1 week |
| Small-institution contract → production live | < 6 weeks |
| Balance verification: unreconciled migrations allowed to cut over | 0, ever |
| Shadow-mode clean streak required before cutover | ≥ 5 business days |
| Design partners live by Month 12 | ≥ 2 |
| Regulatory reports generated without manual edits | > 95% |
| API uptime | ≥ 99.9% |
| Support tickets needing engineering (vs config) | < 20% |

---

## 14. Risks & Mitigations

A detailed risk register is maintained in an internal strategy document.
The engineering-relevant disciplines it produces are public and binding:

- **Scope discipline:** this PRD is the guide — partner requests map to
  configuration first, roadmap second, custom code in core never.
- **Trust building:** open-source code and public test suites from day one;
  shadow-mode deployments; published test methodology (§8).
- **Regulatory change resilience:** versioned country packs and connector
  isolation (§3.1, §4.6) make regulation changes configuration updates, not
  core rewrites.
- **Key-person resilience:** documentation-first culture — this PRD, the ADRs,
  and per-service design docs are maintained from day one.

---

## Appendix A: Ledger Data Model (reference)

- `accounts(id, tenant_id, type, currency, status, customer_ref, created_at, closed_at)`
- `transactions(id, tenant_id, idempotency_key UNIQUE, status, initiated_by, description, created_at)`
- `entries(id, transaction_id, account_id, direction[debit|credit], amount_minor BIGINT, currency, value_date, created_at)` — append-only; **amounts as integer minor units (kobo/cents), never floats; currency on every entry** (§3.1)
- `balances(account_id, currency, current_minor, available_minor, holds_total_minor, updated_at)` — derived, verifiable
- `holds(id, account_id, amount_minor, currency, transaction_ref, expires_at, status)`
- Invariants (**per currency**): per transaction, Σ debit amounts = Σ credit amounts within each currency; globally, Σ all debits = Σ all credits within each currency. Entry currency must match account currency. Cross-currency movement (post-v1): two single-currency legs via an FX position account — never a direct cross-currency entry.

*Working elaboration: [`services/ledger/docs/data-model.md`](../services/ledger/docs/data-model.md).*

## Appendix B: Event Catalog (v1 excerpt)

`account.created`, `posting.completed`, `posting.reversed`, `hold.placed`, `hold.released`, `transfer.initiated`, `transfer.completed`, `transfer.failed`, `customer.created`, `customer.kyc_tier_changed`, `product.published`, `loan.disbursed`, `loan.repayment_received`, `loan.delinquent`, `agent.cash_in`, `agent.cash_out`, `aml.alert_raised`, `report.generated`

---

*This document is the source of truth. Changes require explicit versioning (PR bumping the version header) and team review. Architecture Decision Records (`docs/adr/`) supplement it for individual technical choices.*
