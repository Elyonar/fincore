# Notification v2 — staff notifications, and the service as a platform capability

**Status:** DRAFT · 2026-08-12 — a proposal, not a decision. Nothing here is
implemented. When agreed, this becomes amendments to
[`design.md`](design.md) (AGREED v1.6) plus the ADRs named in §7.

**Why this document exists.** v1 answers one question well: *how does a customer
learn their money moved?* It was never asked the other two — *how does a member
of staff learn something needs them?* and *how does a service other than Core
get a message sent?* Both are now live questions, and answering them by
extending the customer path would produce a service that does neither properly.

---

## 1. What is actually broken today

Three separate things, worth keeping separate.

**a. Staff have no notifications at all.** The bell in the portal is *derived
state* — it re-reads pending approvals, ops cases and messaging suppressions on
a timer and counts them. Nothing is addressed to a person, nothing is stored,
nothing can be read or unread, and nothing survives the condition clearing. A
supervisor who approves a reversal is never told it was theirs to approve; they
find out by looking.

**b. Customer notification is thinner than it looks.** It works end to end, but
the surface is one event and four variables. §5 enumerates it.

**c. Only Core can cause a notification.** The intake has a hardcoded map from
`transfer.completed` to two template keys. Every other publisher on the platform
— the ledger, identity, and Core's own approval and ops-case machinery — cannot
cause a message without editing this service's Java.

---

## 2. The decision this whole document rests on

**A notification's *audience* is a first-class dimension, not a flag.** Staff and
customer notifications differ in nearly every property that matters, and the
temptation is to treat staff as "a customer who happens to work here":

| | Customer | Staff |
|---|---|---|
| Who it is addressed to | one person | a person, or *everyone holding a permission in a unit* |
| Consent | required, per channel, and legally load-bearing | not applicable — it is their job |
| Cost | real money per SMS segment | free |
| Channel | SMS, email — external, via a gateway | in-app first; email as an escalation |
| Delivery guarantee | at-least-once, with retries and receipts | it is on a screen they are already looking at |
| Read state | meaningless — you cannot know | the entire point |
| Quiet hours | protects the customer | would hide an ops case at 2am |
| Retention | as long as the audit needs | until dealt with, then archived |
| Who may see it | only the recipient | the recipient, and anyone auditing the institution |

Almost none of v1's machinery transfers.

**Proposal: this service stays strictly customer-facing, and staff notifications
are written by the deployable that generates the fact.**

An earlier draft of this document proposed the opposite — one service with an
`audience` column — and it was wrong. The argument for merging was that a second
service would duplicate the intake, the dedupe, the epoch fencing and the
templates. Three of those four do not survive contact:

- **The consumption machinery is not duplicated, it is *extracted*.** v1's own
  design says dedupe, epoch fencing and staleness move to `libs/events` when a
  second consumer arrives. A second consumer is the trigger for a planned
  extraction, not a reason to avoid one.
- **The template machinery barely overlaps.** The hard parts — GSM-7 vs UCS-2
  segment counting, channel caps, per-customer locale with fallback, cost
  attribution — are *outbound* problems. A staff message on a screen has none of
  them.
- **The facts that notify staff almost all originate in Core.** An approval
  raised, an approval decided, an ops case opened, a product version published.
  If Core writes the staff notification itself, it commits *in the same
  transaction as the fact*, and there is no event, no broker hop and no window
  where an approval exists that nobody was told about. Routing it through a
  consumer would make that window real and buy nothing.

That last point inverts this document's original cost analysis. §6 previously
said "every staff notification is a new event" — that was only true because it
assumed the wrong topology.

**What each side then is:**

- **Notification service — outbound only.** Gateways, consent, cost, segments,
  delivery receipts, quiet hours, NDPR. One coherent, regulated domain, and the
  one PRD §4.9 and the AGREED design already describe. Its one-paragraph summary
  keeps its current first sentence rather than having "and staff" bolted on.
- **Staff notifications — a small module, owned by the writer of the fact.** An
  inbox table, a recipient set, a read state, and a fan-out rule. No gateway, no
  cost, no consent, no segments.

**The tension this leaves, stated rather than papered over.** Identity-origin
staff notices — a role changed, a sign-in from a new source — would live in
Identity, giving the portal two inboxes to read. For MVP this does not bite:
every staff notification that matters operationally is Core's. When it does,
there are two honest answers (Identity publishes and Core consumes, becoming
that second consumer; or the portal aggregates two inboxes, which is what its
bell already does with three sources). I would not choose now.

---

## 3. Staff notifications

### 3.1 Events, not queues — and why the bell keeps both

The most valuable thing in this section: **an event and a queue are different
things and must not be merged.**

- *"Chidi approved your reversal at 14:03"* is an **event**. It happened once,
  it is true forever, and it is read or unread.
- *"3 approvals are waiting for your signature"* is a **queue**. It is a
  question with a live answer, it self-clears when the work is done, and it has
  no read state that means anything.

v1's bell shows only queues. Storing queue items as notifications produces the
classic failure: eleven unread rows for one approval that was signed an hour
ago, and staff who learn to clear the badge without reading. Storing events only
produces a bell that says nothing when six cases are open.

**Proposal: the bell shows both, labelled.** A "needs you" count derived live
from queues (what it does today, kept), and an inbox of stored events with read
state. Same bell, two sections, one number — the number stays the actionable
one.

### 3.2 Addressing

A staff notification names its recipients one of three ways:

1. **A person** — `userId`. "Your reversal was approved."
2. **A role in a unit** — "anyone holding `approvals:check` in head-office".
   Resolved at send time against the directory, fanned out to one row per
   person, so read state is per person.
3. **A unit** — everyone assigned to it. Rare; branch-wide announcements.

(2) is the one that earns its keep, and the one that needs Identity to answer
*"who holds this permission in this unit"* — an endpoint that does not exist
today (§6).

### 3.3 Delivery: in-app, and deliberately nothing else

A staff notification is a row in an inbox the portal reads. No channel registry,
no gateway, no segments, no cost — none of the outbound machinery, which is the
point of keeping it out of the notification service.

Wording is a short template too, but a far simpler one: one locale, no segment
counting, no channel variants. Whether that is worth a template table at all, or
whether the writing service formats the sentence, is a design question for
step 6 rather than a scope decision.

Email as an escalation (an ops case unread after an hour) is deliberately out.
The moment it is wanted, it is a *customer-shaped* problem — an outbound message
to an address — and it goes through the notification service, with the staff
inbox as its trigger. That is the seam between the two, and it only has to exist
when somebody actually misses something.

### 3.4 What staff would actually be told

Worth listing, because it shows where the work lands:

| Notification | Written by | Cost |
|---|---|---|
| An approval needs your signature | Core (orchestration) | in the transaction that raises it |
| Your request was approved / rejected | Core | in the transaction that decides it |
| An ops case was opened on your transaction | Core | in the transaction that opens it |
| A product version you drafted was published | Core (product) | in the publish transaction |
| Customer alerts are failing | derived, as today | none — it is a queue, not an event |
| Your role or unit changed | Identity | Identity's own inbox, or deferred |
| Somebody signed in as you from a new source | Identity | deferred (§6) |
| End-of-day invariant failed | Ledger | deferred |

**The four that matter operationally are all Core, and all commit beside the fact
they describe.** No event, no broker, no window in which an approval exists that
nobody was told about. That is the whole argument for this topology in one line.

---

## 4. Making it usable by other services

### 4.1 Routing becomes data, not Java

Today: a `Map` in `EventIntake` from `transfer.completed` to two sides.

Proposal: a `subscriptions` table — *(event type, audience, category, recipient
rule, template key)* — seeded per tenant, editable by an administrator holding
`policy:write`. Adding "tell the customer when their account is closed" becomes a
row, not a release.

**What must not be lost:** D-3 in the AGREED design — *one notification category
has exactly one publisher* — is the rule that stops one business moment
producing two messages. A subscriptions table makes it easy to violate by
accident, so it needs the same rule enforced as a constraint: **unique
(tenant, business moment kind, audience, category)**, with a validation refusing
a subscription whose event names a moment another subscription already claims.

### 4.2 The payload problem — the largest single gap

Templates interpolate `{{variable}}` **from the event payload only**. Core's
transfer payload is, in full:

```json
{"transactionId":"…","amountMinor":"250000","feeMinor":"0","currency":"NGN"}
```

That is everything a template can say. There is no account number, no balance,
no counterparty, no description, no reference, no date, and `amountMinor` is
unformatted minor units. A real Nigerian bank SMS reads:

> `Acct: *0001 Amt: NGN25,000.00 CR Desc: DEPOSIT Bal: NGN47,450.00 12-Aug-26`

**None of those fields can be written today.** This is why the templates I
authored during testing are static sentences — nothing else was available. Any
scope that says "customer notifications work" without fixing this is wrong.

Three ways out, and the choice matters:

1. **Fatten the event payload.** Cheapest, and wrong: ADR 0008 makes the payload
   a published contract, and putting a customer's balance on the broker is
   customer data at rest in Kafka for every consumer and every retention window.
2. **Notification enriches by calling back.** It already calls Core twice per
   event for accounts and contact; a third call for the transaction's
   presentation detail is consistent with that, keeps customer data off the
   broker, and costs latency the send path can afford. **Recommended.**
3. **A rendering context service.** Over-engineering at this size.

Either way, formatting (money, dates, masked account numbers) belongs in the
renderer as **filters** — `{{amount | money}}` — not in each of a hundred
templates, and definitely not in each tenant's copy of them.

### 4.3 Seeded templates — the platform's vocabulary, the tenant's sentence

An empty template table is why a clean install sends nothing: the first
transaction on a new deployment is suppressed `NO_TEMPLATE`, and the operator's
only clue is a reason code. It is also why every institution would write the same
six messages, badly, and get the segment count wrong the first time somebody
translates one into Yoruba.

**ADR 0017 already decided this shape for a different vocabulary** — *permissions
are the platform's, roles are the tenant's* — and the mapping is close to exact:

| Platform owns | Tenant owns |
|---|---|
| which business moments notify | whether a category sends at all, on which channels |
| the template **keys** (`debit.alert`, …) | the **wording** |
| the **variables** each key may use | translations into Hausa, Yoruba, Igbo |
| a starter set, published, in English | any fork of it |

**Seeded, not empty.** Each tenant is provisioned with a published English
starter template per key and channel, marked `origin = PLATFORM`. A new
institution therefore notifies correctly on its first transaction with no
configuration at all — which is the behaviour a bank expects and the opposite of
what happens today.

**Extended by forking, never by editing.** A tenant changing the wording creates
their own version of that (key, channel, locale); it supersedes the platform one
by the existing highest-published-version rule. Platform rows are never edited in
place, so a starter can be improved in a later release without silently
rewriting a message an institution deliberately changed. `origin` is what makes
"has this tenant customised it" answerable, and what lets the portal show
"platform default" beside "yours".

**The starter set** — the moments a Nigerian MFB actually sends on, which is a
product decision the platform is better placed to make once than a hundred
administrators are to make separately:

`debit.alert` · `credit.alert` · `reversal.alert` · `account.opened` ·
`account.closed` · `low.balance` · `statement.ready` · `otp` *(when Identity can
ask — gap 6)*

**And the bodies have to be real.** The wording below is only writable once §4.2
lands; a starter set on today's four variables would seed the same uninformative
sentence into every institution:

```
Acct: {{accountMask}}  Amt: {{amount | money}} {{drCr}}
Desc: {{narration}}  Bal: {{balance | money}}  {{valueDate | date}}
```

Seeded templates and the render context are therefore **one piece of work, not
two**. Shipping the seeds first would bake in exactly the sentence this document
exists to complain about.

### 4.4 What "flexible and robust" costs

To be blunt about the trade: a subscriptions table plus enrichment plus filters
makes the service configurable by an administrator instead of by a release. It
also makes it possible to configure a template that renders empty, addresses
nobody, or costs three segments in Yoruba. v1 already has the guards for the
last two (`missingVariables` suppression, `overLimit` refusal at publish). The
new one needed is a **preview**: render a subscription against a sample payload
before it is saved. Without it, the first time anyone sees the wording is on a
customer's phone.

---

## 5. Customer notification gaps, enumerated

Ordered by how much they matter, with an honest severity.

| # | Gap | Severity |
|---|---|---|
| 1 | **Templates can say almost nothing** — four variables, no balance, account, description, reference or date; no formatting (§4.2) | **blocks real use** |
| 2 | **One event consumed.** Nothing for failed transfers, reversals, account opened/closed, KYC tier change, statement ready | **blocks real use** |
| 3 | **No delivery at all** — the connector is unbuilt, `SENT` means "a log sender accepted it" | known, deliberate |
| 4 | **No delivery receipts, no cost** — PRD §4.9 asks for cost reporting per tenant; v1 counts segments, not money | needs the connector |
| 5 | **Reversal event name unsettled** — design says `transfer.reversed`, code emits `transfer.reversal_initiated`; a reversal notifies nobody today | real, small |
| 6 | **No OTP path.** PRD §4.10 puts SMS OTP through this gateway; Identity has no way to ask | blocks MFA |
| 7 | **No customer preferences** beyond consent — no per-category opt-out, no preferred channel, no preferred locale on the customer record | real |
| 8 | **No quiet-hours override for the alerts a customer is owed** — a debit alert held until 07:00 is worse than useless | real |
| 9 | **No resend / no operator retry** of a failed message | operational |
| 10 | **Error catalog test PLANNED**, metrics absent (`testing.md`) | hygiene |
| 11 | **Poison-pill retries are silent** — a permanently failing event retries ~1/sec forever, logging nothing at any level | **operational risk** |

(11) is not strictly a v2 feature but it is the one that will cost somebody a
night, and it is a two-line fix plus a log line.

---

## 6. What has to change outside this service

Smaller than the earlier draft claimed, because staff notifications no longer
travel over the broker.

- **Core** gains a `staff-notification` module: an inbox table, a fan-out rule,
  a read state, and a read API. Written transactionally by the approval, ops-case
  and product-publish paths that already exist. **No new events.**
- **Identity** answers *"who holds permission P in unit U"* — the one genuinely
  new endpoint, needed for role-based fan-out (§3.2).
- **Core (customer)** serves presentation detail for enrichment (§4.2), and
  gains customer notification preferences (gap 7).
- **Identity** eventually publishes — needed for staff security notices and for
  OTP (gap 6), and it has an audit table but no outbox and no publisher. This is
  now the *last* dependency rather than the largest, and MVP does not need it.
- **`libs/events`** takes the consumer-side machinery if and when a second
  consumer arrives, exactly as v1's design already says.

---

## 7. Decisions that need an ADR

Cross-cutting, so per `AGENTS.md` these are ADRs, not CHANGELOG entries:

1. **Notification is outbound-only; staff notifications belong to the writer of
   the fact** (§2). The consequential one — it settles what this service is, and
   it contradicts the merged shape an earlier draft proposed.
2. **Enrichment over payload-fattening** — customer data stays off the broker;
   the consumer calls back (§4.2). This one has a data-protection argument and
   should be recorded as such.
3. **Templates are seeded platform content a tenant forks** (§4.3) — the same
   split ADR 0017 made for permissions and roles, applied to wording.
4. **Subscriptions as data** — and the uniqueness rule that preserves D-3 (§4.1).
5. **Identity gains an outbox** — later, and only when staff security notices or
   OTP are wanted (§6).

---

## 8. Suggested order, with honest sizes

Each step is independently useful and independently shippable.

| # | Step | Size | Why here |
|---|---|---|---|
| 1 | Silent-retry fix + error catalog test + metrics | S | Operational risk, unrelated to everything else, do it first |
| 2 | Enrichment + rendering filters (§4.2) | M | Nothing else is worth doing while templates can say nothing |
| 3 | Seeded starter templates, `origin`, tenant fork (§4.3) | M | Ships with (2) or it seeds an empty sentence |
| 4 | Subscriptions table + preview, replacing the hardcoded map | M | Makes every later notification a row |
| 5 | More customer moments (reversal, account opened/closed, failed) | S | Free once (3) and (4) land — a seed row and a subscription row |
| 6 | Core `staff-notification` module: inbox, fan-out, read state, API | M | No events; written beside the facts that cause it |
| 7 | Identity: who-holds-permission-P-in-unit-U | S | The one new endpoint staff fan-out needs |
| 8 | Portal: bell shows inbox + queues, labelled (§3.1) | M | The visible payoff |
| 9 | Customer preferences + quiet-hours override | M | |
| 10 | Identity outbox → staff security notices, OTP | L | Only when wanted |
| 11 | Messaging connector, DLR, cost | L | Everything above is real the day this lands |

Steps 1–5 make the *customer* side genuinely usable and are all in this service.
6–8 deliver staff notifications and are mostly **not**. 9–11 are the tail.

---

## 9. Open questions — I need answers before this becomes a design

1. **Is in-app enough for staff v1, or must ops cases escalate to email?** I
   would ship in-app only and add escalation when somebody misses something.
2. **Should staff notifications be tenant-scoped only, or also visible to an
   operator across tenants?** ADR 0015's operator console is a different app; I
   assume tenant-scoped and no cross-tenant read.
3. **Who authors staff templates?** Customer templates are tenant content
   (PRD §4.9). Staff wording is arguably *platform* content — every institution
   wants "an approval needs your signature" to say the same thing. Tenant-owned
   means a hundred banks writing the same sentence badly.
4. **Retention.** How long does a read staff notification live? Customer
   messages have an audit answer; staff ones do not yet.
5. **Does a staff notification about money need to respect the same tenant
   isolation as the money?** I assume yes and would put it under the same RLS.
