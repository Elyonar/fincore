# Platform — The Operator Console

**Status:** DRAFT v1.0 (2026-08-08) — amendments via [`CHANGELOG.md`](CHANGELOG.md)

The onboarding dashboard: the surface FinCore's own staff use to bring an
institution onto the platform. It is a client of this service exactly as the
teller app is a client of Core — it holds a session, it calls one origin, and it
has no privilege of its own.

Written here rather than in a separate repository's README because the screen
inventory and the API surface have to be designed against each other. ADR 0014
records why: list and search endpoints are designed as a batch against a screen
inventory, *not discovered one 404 at a time*.

---

## 1. Shape

**Next.js, App Router, TypeScript.** The choice is recorded rather than assumed:
this console has a server-rendered shape (tables, forms, a polling detail view)
and no offline story, and the one property that decides it is §2 below — Next.js
has a server, and the highest-privilege client on the platform should not hold
its own token in a browser.

**A separate origin, and a separate deployment, from the tenant edge.** ADR 0015:
the two have different audiences, different realms and different exposure. An
operator surface reachable wherever a teller surface is reachable is an operator
surface with the attack surface of a teller surface.

## 2. Identity

**A confidential client with a server-side session, not a browser-held token.**

The tenant SPA is a public client holding its own access token, and that is
correct for it: the token grants one institution's staff their own institution's
permissions, and it is short-lived. This console's token can create and suspend
institutions. Next.js has a server; the token lives there, the browser holds a
session reference, and the console's server is the only thing that ever presents
a credential to this service.

This is a different decision from ADR 0014's, made for a different client, and
the reason is the blast radius of the token rather than a change of heart about
PKCE.

Operators authenticate against the **platform realm** — never a tenant realm.
Their tokens carry no tenant claim, which is why they cannot be replayed against
Core, Notification, or the ledger even if one leaked: `libs/auth` refuses a token
with no tenant, and the ledger refuses it explicitly.

Route protection mirrors the permission vocabulary in
[`api.md`](api.md) §2. The console renders only what the operator's permissions
allow, and — this is the part that matters — **the server enforces nothing the
service does not also enforce.** A hidden button is a courtesy; the refusal is
the control.

## 3. Screens

Every row names the endpoints it consumes. A row with no endpoint is not a
screen, it is a request for backend work.

| Screen | What it does | Endpoints |
|---|---|---|
| **Sign in** | Platform-realm login | identity provider only |
| **Tenants** | The list: status chips, segment, created, last run outcome. Filter by status, search by name or realm. Keyset paged. | `GET /v1/tenants` |
| **New tenant** | The intake form: legal name, display name, realm name, country, segment, web origin. Saves a `DRAFT`; nothing external happens. | `POST /v1/tenants` |
| **Tenant detail** | The spine of the console. Header with state; four panels below. | `GET /v1/tenants/{id}` |
| — *Provisioning panel* | Per-participant progress; the run's three-valued outcome; the one-time secret on success | `POST`/`GET /v1/tenants/{id}/provisioning-runs`, `GET /v1/provisioning-runs/{runId}` |
| — *Readiness panel* | The `CONFIGURING` checklist with completion state and the go-live gate | `GET /v1/catalog/readiness-items`, `POST /v1/tenants/{id}/readiness/{item}`, `POST /v1/tenants/{id}/go-live` |
| — *Access panel* | Administrators seeded, client secret rotation, web origin | `POST /v1/tenants/{id}/administrators`, `.../client-secret/rotate`, `.../web-origin` |
| — *Audit panel* | Every action on this tenant, who made it, who checked it | `GET /v1/tenants/{id}/actions` |
| **Edit draft** | Amend or discard before provisioning | `PATCH` / `DELETE /v1/tenants/{id}` |
| **Suspend / Resume / Close** | Maker-checked dialogs; second operator confirms | `POST /v1/tenants/{id}/suspend`, `/resume`, `/close` |
| **Run history** | Every attempt on a tenant, including compensated ones, with the reason | `GET /v1/tenants/{id}/provisioning-runs` |

## 4. The provisioning experience

The screen this console exists for, and the one most likely to be built wrong.

The operator fills in the intake form, saves a draft, reviews it, and presses
Provision. From that moment the console is an observer: it holds a run id and
polls, rendering five participants as they settle.

**Three outcomes per step, and three renderings.** Succeeded, refused, and *not
yet known*. The third is not a spinner and not an error — a spinner implies
progress that may not be happening, and an error invites an operator to act on a
step that may have succeeded. It reads as what it is: the answer is outstanding
and the platform is finding out. No retry button, no cancel button; a second run
is refused by the protocol anyway.

**Refusal is legible.** The panel names the participant, shows that participant's
own error code, and states plainly that everything already created was undone.
The affordance is *fix the input and try again*, and trying again is a new run
with a new idempotency key.

**Success delivers the client secret exactly once.** It is shown, it is copyable,
and it says clearly that it will not be shown again — because
[`design.md`](design.md) decision 13 means the service genuinely cannot show it
again. An interface that implies otherwise turns a deliberate security
property into a support ticket.

**Success is not "done".** The tenant moves to `CONFIGURING` and the readiness
checklist becomes the next thing on the screen. That is the whole reason the
lifecycle has a state between provisioned and live: a bank with a realm and four
registry rows is a bank that cannot take a deposit yet.

## 5. What the console does not do

Boundaries, because a control-plane UI is where features accumulate.

- **It never shows a tenant's business data** — no customers, no balances, no
  transactions. There is no endpoint, by design (`design.md` §2), and asking for
  one is a change to that design rather than a feature request.
- **It never configures the institution.** Products, org units, staff, tills and
  approval tiers are the tenant administrator's job, done in the tenant's own
  admin console against Core. This console tracks *that they were done*.
- **It is not a support tool.** Acting inside a tenant on its behalf needs a
  consent and impersonation story that does not exist and should not be improvised
  here.
- **It has no offline mode and no queue.** Provisioning is deliberate, rare, and
  performed at a desk.

## 6. What has to exist before a line of it is written

The honest dependency list. Every endpoint in §3 is unbuilt today; so is the
service behind them and the realm the operators live in. In order:

1. This service exists, with the endpoints in [`api.md`](api.md).
2. The platform realm exists, with the two job composites in `api.md` §2.
3. The three participants implement the internal contract in
   [`provisioning-protocol.md`](provisioning-protocol.md) §3, and the ledger's
   trusted-caller list names the control plane.
4. An admin edge exists — one origin for the console, separate from the tenant
   edge.

Only then does the console have anything to call. Building it against mocks
first is reasonable and the API in this document is stable enough to mock
against; shipping it before item 3 means shipping a dashboard that creates
tenants Core will 404, which is the defect this whole design exists to remove.
