# Notification — Data Model

**Status:** AGREED v1.5 (2026-08-08) — amendments via [`CHANGELOG.md`](CHANGELOG.md)

One schema, `notification`, in its own database. Seven tables: six the design
names, plus Flyway's own history.

Migrations: `V1__schema.sql` (schema, roles, tenant and worker functions),
`V2__notification_tables.sql` (everything below), `V3__tenant_default_locale.sql`
(the language a tenant writes in when the customer never said).

## The tables

| Table | Holds | The rule it exists to enforce |
|---|---|---|
| `channels` | id, address_kind, required_parts, content_model, max_units, enabled | A channel is **data**. Adding one is an INSERT and a sender class, never a migration |
| `templates` | tenant, key, channel, locale, version, status, parts, units | Versions are append-only; a published one is immutable and carries every part its channel requires |
| `channel_policy` | tenant, category, ordered channels, timezone, default locale, quiet window | Per tenant and category. A window with one end cannot be expressed; the default locale is NOT NULL because a fallback without one is not a fallback |
| `consumed_events` | publisher, event_id, tenant, occurred_at, epoch, disposition | `(publisher, event_id)` unique — the price of at-least-once, and every event reaches a terminal disposition |
| `notifications` | tenant, business moment, category, channel, template version, recipient, address, rendered, units, state | One message per `(tenant, moment, category, channel, recipient)`; terminal states are terminal |
| `delivery_attempts` | notification, attempt_no, outcome, client_reference, gateway_ref | Append-only. History is how a delivery dispute is answered |
| `suppressions` | the event or moment, reason_code, detail | Why a message was **not** sent. The service's defining invariant lives here |

## Rules that apply to every table

- **`tenant_id` on every row**, and row-level security **enabled and `FORCE`d**.
  `channels` is the one exception and deliberately so: which channels the
  platform can speak is a deployment fact, not a tenant's.
- **Tenant context via `SET LOCAL` inside the transaction**, never a session
  `SET`. Connections are pooled across tenants, and a session variable hands the
  next borrower the previous tenant's identity — the failure row-level security
  exists to catch.
- **Two roles, and neither is the owner.** `notification_app` serves requests and
  intake; `notification_worker` claims the queue across tenants through a narrow
  worker policy rather than `BYPASSRLS`, which would exempt it from every table
  at once. Migrations run as the owner; traffic never does.
- **Grants are the second boundary.** The app role has no `UPDATE` on
  `delivery_attempts`; the worker role has no read of `templates`. Neither is
  something either needs, and a grant is enforced before any trigger runs.

## Decided edge cases

**Two publishers, one moment.** The dedupe key is `(publisher, event_id)`, and
it deliberately does *not* prevent two messages when two different events
describe the same transfer — it cannot, because they are different events. The
unique key on `notifications` is what does, and it is a separate mechanism for a
separate failure (design D-3, D-4).

**Both sides of a transfer.** `one_message_per_moment` includes the recipient, so
one business moment owing an alert to a sender *and* a receiver is two rows and
not a conflict. An intra-tenant transfer is exactly that case.

**A published template is immutable, including its measurement.** `units` is
computed at publish and stored, so a message's cost is reconstructible from the
version that produced it even after the segment rules change.

**`parts` is JSONB, not columns.** Which parts exist is the channel's business:
SMS has a body, email has a subject and body, push would have a title and body.
The trigger validates a published template against its channel's
`required_parts`, which is where the `NOT NULL` guarantee went when the columns
did (CHANGELOG v1.1). It must not weaken in the move, so the schema-enforcement
suite asserts it.

**`consumed_events` is tenant-scoped even though its key is global.** Two
publishers may both number an event 1, so the unique key cannot include the
tenant; but a consumer able to read another tenant's event history would leak
which tenants are busy, and there is no reason it needs to.

**Locale lives in two places, and neither is a duplicate.** The customer's own
language belongs to Customer and is nullable — a person nobody asked is
ordinary. The tenant's default belongs here and is NOT NULL, because a fallback
with nothing behind it is not a fallback. The notification row records the locale
the template was actually chosen in, which is neither of those: it is what was
sent, and it stays true after either setting changes.

**A suppression may reference an event, a moment, or neither precisely.** Its
columns are nullable on purpose: `UNKNOWN_ACCOUNT` has no recipient to record,
and `STALE_EVENT` has no channel. The reason code is what is never null.

**No foreign key leaves this schema.** `recipient_ref` is Core's customer id and
`business_moment_key` embeds a Core saga id; neither is a reference the database
can enforce, because both live in another deployable's database. They are
recorded as opaque identifiers, and the design says so rather than implying a
constraint that cannot exist.
