# Notification — Design Changelog

Amendments to the **agreed** Notification design. Process and entry format:
[`docs/conventions/design-changes.md`](../../../docs/conventions/design-changes.md).

The version here matches the status header in every Notification design doc.
Newest entry first.

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
