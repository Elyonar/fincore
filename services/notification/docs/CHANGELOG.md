# Notification — Design Changelog

Amendments to the **agreed** Notification design. Process and entry format:
[`docs/conventions/design-changes.md`](../../../docs/conventions/design-changes.md).

The version here matches the status header in every Notification design doc.
Newest entry first.

---

## [1.6.0] — 2026-08-11 · MINOR

**The address cipher refuses to bluff in production.** `AddressCipher` substituted a committed
development key for a missing `fincore.notification.address-key` with only a log warning — PII
encrypted under a key anyone with the repository holds, in any environment. It now refuses to
start outside the `dev`/`test`/`local` profiles when the property is blank, mirroring Identity's
signing-key double lock: dev convenience stays, production silence does not. A tightening, so
MINOR; no working deployment can have been relying on the substitute knowingly, and one that was
relying on it unknowingly is the reason this entry exists. Covered by `AddressCipherTest`.

## [1.5.1] — 2026-08-08 · PATCH

**The `tenants` table gets documented, and an off-by-one is chased out.**

- **Docs:** `data-model.md`, `design.md`, `testing.md`, `README.md`
- **Why:** v1.3.0 added `V4__tenant_registry.sql` and named `data-model.md` in
  its Docs list, but the table was never added to the table list — so the schema
  has carried a table the data model does not mention for two versions. A reader
  reconstructing the design from the docs would have missed the gate both
  entrances consult.
- **What changed:**
  - `tenants` joins the table list, and the row-level-security rule now names
    **both** deliberate exceptions rather than one. `channels` is a deployment
    fact; `tenants` is what a caller consults *before* it has a tenant context,
    so a policy on it would be a lock whose key is inside the box.
    `SchemaTest.rls_is_forced` already excluded both by name — the doc had not
    caught up.
  - The table count is corrected wherever it appears. It was wrong in three
    directions at once: the count started at "six" in
    `V2__notification_tables.sql`'s header comment (which creates **seven**),
    v1.3.0's entry called `tenants` "the seventh table" (it is the **eighth**),
    and the docs inherited the error. `SchemaTest.the_tables_exist` has asserted
    all eight throughout — the code was never wrong, only the prose about it.
  - `testing.md` records the intake suite at its true size, 20 rather than 15.
  - The service README's status block and memory map, which still advertised
    v1.2/v1.1 and claimed `api.md` had "none built yet" while the paragraph
    above it said every documented endpoint exists.
- **Impact:** none. No schema, API, error code, invariant or guarantee moves;
  this is the documentation catching up to a contract agreed in v1.3.0.
- **Tests:** unchanged, and none needed to change — the drift was one-way, docs
  behind code. `SchemaTest`'s display name is corrected from "seven" to "eight"
  in the same breath, being a label rather than an assertion.

**Not corrected:** `V2__notification_tables.sql`'s "The six tables" header
comment. Flyway checksums the whole file, so editing an applied migration breaks
validation on every database that has run it. The comment is wrong and stays
wrong; this entry is where that is recorded.

---

## [1.5.0] — 2026-08-08 · MINOR

**The queue's documented alarms get measurements.**

- **Docs:** `testing.md`
- **Why:** the README's known-limitations table said it plainly: "nothing
  exports a queue depth or a suppression rate, so no alarm in the design has a
  measurement behind it."
- **What changed:** `SendMetrics` exposes `/actuator/prometheus` gauges —
  queue depth (PENDING + SENDING), oldest-pending age (the delivery-delay
  alert's number), and exhausted-attempts count. Reads run in worker scope in
  their own transaction, from the table on every scrape, so a stalled worker
  still reports its backlog honestly. Segment *cost* (money per message) still
  waits on a gateway that can report it; the README row narrows accordingly.

---

## [1.4.0] — 2026-08-08 · MINOR

**What happens when the listener throws is now stated, not defaulted.**

- **Docs:** `design.md`, `testing.md`
- **Why:** `EventListener`'s contract says an escaping exception keeps the
  offset put and the event is redelivered. Spring Kafka's default error handler
  honours that for ten attempts, then logs and *advances* — so any Core outage
  longer than a few seconds silently lost events, which is the one failure this
  service's own javadoc says it must never have. The contract was true of the
  code and false of the deployment.
- **What changed:**
  - **`KafkaErrorHandling`**: transient failures retry forever (fixed backoff,
    offset held — delivery is late, never silently never); a
    `MalformedEnvelope` is skipped loudly at ERROR with topic/partition/offset,
    because it can never succeed and retrying it forever parks the partition
    behind a poison message. A dead-letter topic can replace the log when a
    gateway exists to alert on one.
  - **A dev-tenant seeder** (`fincore.notification.dev-tenant-id`,
    compose-only, loud), matching the ledger's and Core's: without it a fresh
    stack dropped every event at the tenant gate — silently, which is exactly
    the posture this service exists to refuse.
  - The dead `ALREADY_HANDLED` → `IGNORED` mapping in `EventIntake.record` is
    gone; the disposition returns before recording, so the mapping was
    unreachable and read as a behaviour that did not exist.

---

## [1.3.0] — 2026-08-07 · MINOR

**A tenant must exist — on both doors. `V4__tenant_registry.sql`.**

- **Docs:** `data-model.md`, `architecture.md`, `testing.md`
- **Why:** the same gap Core had, doubled. This service has two entrances and
  neither proved a tenant was real: a request arrives with a validated token, and
  an event arrives with a tenant in its envelope. An event naming a tenant nobody
  provisioned here would have accumulated suppressions — and eventually
  messages — under an id that means nothing.
- **Impact:** requests for an unknown or suspended tenant are refused with 404
  before any handler. Events for one are **suppressed with `UNKNOWN_TENANT`**,
  not thrown: an exception would stall the consumer on every poll and stop the
  queue for tenants that are perfectly real. It is the service's founding rule
  applied to itself — every consumed event ends as a message or as a reason.
- **Supersedes:** `EventIntake`'s `@Transactional`. The intake now opens its
  transaction explicitly, because the tenant scope is a `SET LOCAL` that
  evaporates without one — and an annotation only applies through a proxy. A
  directly constructed intake ran every statement unscoped, which row-level
  security caught the moment a test wrote a suppression for a foreign tenant.
  The send worker had already been converted for the same reason; this is the
  second instance of one pattern.
- **Tests:** `EventIntakeTest.an_unknown_tenant_is_refused_on_the_event_path`,
  plus `SchemaTest` updated for the seventh table and its deliberate absence
  from row-level security — the registry is what a request consults *before* it
  has a tenant context to be scoped by.
- **Migration:** `V4__tenant_registry.sql`, backfilling from existing policy and
  templates.

---

## [1.2.0] — 2026-08-06 · MINOR

**Locale is selected, not assumed. `V3__tenant_default_locale.sql`.**

- **Docs:** `design.md` (D-24), `data-model.md`, `testing.md`
- **Why:** v1.1 shipped with the locale hardcoded to `en` in the intake, with a
  comment saying why: the schema keyed templates by locale, but no per-customer
  language existed to select with. Core v1.10 adds it, so the gap closes. The
  comment was honest and the behaviour was still wrong — a platform whose first
  market speaks Hausa, Yoruba and Igbo cannot ship a sender that only writes
  English.
- **Impact:** internal. `channel_policy` gains `default_locale`, NOT NULL with
  `'en'` — unlike the customer's own locale, a tenant must always have a language
  to fall back to or the fallback is not one. The notification row now records the
  locale the template was actually chosen in, rather than a constant.
- **The selection is a chain, and the fallback is the point:** the customer's
  language, then the tenant's default. A locale with no published template falls
  back rather than suppressing, which is what makes translating one alert at a
  time safe — adding a Yoruba-speaking customer must not silence them until
  somebody finishes translating. Only when *no* preferred locale has a template
  is it `NO_TEMPLATE`, and the suppression names the locales it tried.
- **Supersedes:** `EventIntake.LOCALE`, and the design note explaining its
  existence.
- **Tests:** four new cases in `EventIntakeTest` — the customer's language wins;
  an untranslated locale falls back; the tenant's default is the tenant's rather
  than the platform's; and no template in any preferred language records which
  ones were tried.
- **Migration:** `V3__tenant_default_locale.sql`.

---

## [1.1.0] — 2026-08-06 · MINOR

**A channel is a registry row, not an enum. Adding one costs no migration.**

- **Docs:** `design.md` (D-13, D-14, D-15), `README.md`
- **Why:** v1.0 treated SMS and email as the two channels and wrote their
  differences into the schema — a `subject` column that email required and SMS
  left null, a segment count that only SMS used, and a `CHECK` list naming both.
  That design answers "what if we add push, or WhatsApp?" with *a migration, a
  schema change, and an edit to every table that names a channel*. Push is in the
  PRD (§4.9) and would have paid that cost on arrival.
- **Impact:** internal; nothing is built yet, so no caller and no data are
  affected. Channels become rows in a `channels` registry carrying the only four
  properties that actually vary — address kind, required parts, content model,
  cost unit — and `templates`, `notifications` and `channel_policy` hold foreign
  keys to it. Template content becomes `parts JSONB` validated against the
  channel's `required_parts`.
- **The cost, recorded rather than elided:** "email has a subject" moves from a
  `NOT NULL` column to publish-time validation plus a trigger. The guarantee
  moves; it must not weaken, which is why the schema-enforcement suite gains a
  case asserting a template missing a required part is rejected.
- **Supersedes:** v1.0's fixed `subject`/`body` columns, its SMS-specific
  segment logic, and its two-channel framing throughout.
- **Tests:** a new *channel registry* suite — a channel added by row alone
  reaches send with no code outside its sender; an enabled channel with **no**
  sender fails startup rather than queueing invisibly; fallback exhaustion
  suppresses with a reason. Invariant 9 is new and states the startup check.
- **Migration:** none — no schema exists yet.

*WhatsApp appears in this design as a worked example of what a new channel
costs. It is **not** in the PRD, which names SMS, email and push only. Making it
cheap to add is a design decision; deciding to support it is a product one, and
belongs in a PRD version bump at the point it is taken.*

---

## [1.0.0] — 2026-08-06 · AGREED baseline

**Initial agreed design.** Implementation may begin.

- **Docs:** [`design.md`](design.md), [`README.md`](../README.md)
- **Scope:** one deployable, one PostgreSQL database, one schema. It consumes
  domain events and turns them into messages over SMS and email. It writes no
  money, publishes no events, holds no gateway credentials, no customer profile
  and no consent records.
- **The central guarantee:** every consumed event reaches exactly one terminal
  disposition — a message, or a suppression carrying a reason code. "Why did my
  customer not get this?" is answerable from the database, always.
- **The double-send is prevented structurally, not deduped away.** One
  notification category has exactly one publisher, because the ledger's
  `posting.completed` and Core's `transfer.completed` describe one business
  moment and dedupe on `(publisher, eventId)` cannot see that they are the same
  moment.
- **Two independent replay guards:** the consumer starts at latest, *and* events
  older than `max-event-age` are suppressed — because a consumer offset is an
  operational detail somebody eventually resets.
- **Transactional alerts are exempt from quiet hours and cannot be opted out
  of.** A debit alert is a fraud control; one held until 07:00 about a 02:00
  debit is a receipt.
- **Retries on an unknown outcome, never at-most-once.** A duplicate debit alert
  is an annoyance; a missing one is a control that never fired. This is the
  opposite of what an OTP path needs, which is why OTP bypasses this service and
  calls the messaging connector directly (PRD §4.10).
- **It is the platform's first event consumer.** Consumer-side dedupe, epoch
  fencing and replay safety are built here and extracted to `libs/events` at the
  second consumer.
- **Tests:** every suite runs against real PostgreSQL. Hard rule 7's money-path
  merge gate does not attach — this service moves no money, and the design says
  so rather than leaving it to be inferred.

**Known follow-ups**, tracked and blocking a real send:

1. Core gains an account → contact-and-consent lookup. `api.md` has no reverse
   lookup from an account, and without it this service cannot address anyone.
   Lands as a Core amendment.
2. [ADR 0011](../../../docs/adr/0011-first-consumer-before-phase-three.md)
   records building this ahead of PRD §9's Phase 3, since Phase 0 excludes
   notifications by name.
3. Nothing is delivered anywhere until the messaging connector exists. v1's
   `log` adapters announce that at startup.

*Four questions were decided by the owner at agreement time, and are recorded
because their alternatives were live: SMS **and** email in v1 rather than SMS
alone; publish no events; retry unknown sends; consent stays in Customer.*
