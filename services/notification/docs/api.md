# Notification — API Surface (v1)

**Status:** AGREED v1.4 (2026-08-08) — amendments via [`CHANGELOG.md`](CHANGELOG.md)

> **Every route below exists, and the build fails if that stops being true.**
> `ApiTest.the_document_and_the_code_agree` compares this table against the
> served routes in both directions — documented but not served, and served but
> not documented — so the drift Core's CHANGELOG v1.6 had to correct cannot
> happen here quietly. A positive spot-check cannot find an absence, which is why
> it is a set comparison rather than a list someone remembered to update.

REST/JSON. Every request carries a validated identity token — **the tenant comes
from the token, never a header** (ADR 0009) — and every endpoint denies by
default: the permission named below is required.

There is no endpoint that sends a message. Messages are owed by events, never
requested by callers; an endpoint that injected one would be a way to send a
customer anything with no domain event behind it.

## Endpoints

| Method & path | Purpose | Permission | Caller |
|---|---|---|---|
| `GET  /v1/templates` | list templates and their live versions | `notifications:read` | admin |
| `POST /v1/templates` | create a template with a DRAFT version 1 | `templates:create` | admin |
| `POST /v1/templates/{id}/versions/{version}/publish` | publish a version (attributed; measured at publish) | `templates:publish` | admin |
| `GET  /v1/policy` | the tenant's channel order and quiet hours, per category | `notifications:read` | admin |
| `PUT  /v1/policy/{category}` | set channel order, timezone and quiet window | `policy:write` | admin |
| `GET  /v1/deliveries` | delivery state by business moment or recipient | `notifications:read` | ops, support |
| `GET  /v1/suppressions` | **why messages were not sent**, by moment or reason | `notifications:read` | ops, support |

`GET /` answers with the service identity and its documentation links, and
`/actuator/health` with liveness. Neither is part of the v1 contract, and both
are open by design so a load balancer need not hold a token.

## The contract property that matters most

**`GET /v1/suppressions` is not a debugging convenience.** It is the endpoint
that makes the service's defining invariant usable by a human: every consumed
event ends as a message or as a suppression, so "why did my customer not get
this?" is a query. A support team without it reads logs and guesses, and a
guess about whether a customer was told their account moved is not an answer.

## Error catalog

Per [`error-contract.md`](../../../docs/conventions/error-contract.md): a
machine-readable `code`, a `reason` where one code spans several causes,
`details` carrying the facts a message would interpolate, and a `message` that
is developer English nobody displays or parses.

| Code | Meaning | Retry? |
|---|---|---|
| `TEMPLATE_NOT_FOUND` | no such template for this tenant → 404 | no |
| `TEMPLATE_PART_MISSING` | a published version lacks a part its channel requires → 422 | no |
| `TEMPLATE_TOO_LONG` | rendered worst case exceeds the channel's `max_units` → 422 | no |
| `TEMPLATE_ALREADY_PUBLISHED` | a published version is immutable → 409 | no |
| `UNKNOWN_CHANNEL` | not a channel in the registry → 422 | no |
| `CHANNEL_DISABLED` | in the registry, not enabled in this deployment → 422 | no |
| `POLICY_INCOMPLETE` | a quiet window with one end, or an empty channel list → 422 | no |
| `UNKNOWN_CATEGORY` | not `TRANSACTIONAL`, `SERVICE` or `MARKETING` → 422 | no |

Not-found and wrong-tenant are deliberately indistinguishable, as everywhere on
this platform: distinguishing them confirms that a record exists somewhere.

## Suppression reasons

Returned by `GET /v1/suppressions` and enumerated in `Suppressed`. A closed set,
never free text — an explanation living only in an English sentence is one a
caller has to parse, and a platform serving Lagos and Abidjan cannot write that
sentence for either.

`STALE_EVENT` · `EPOCH_FENCED` · `UNKNOWN_ACCOUNT` · `NO_ADDRESS` ·
`OPTED_OUT` · `QUIET_HOURS` · `NO_TEMPLATE` · `MISSING_VARIABLE` ·
`TOO_MANY_UNITS` · `NO_POLICY` · `ATTEMPTS_EXHAUSTED`

The catalog test that keeps this list and the `Suppressed` enum from drifting is
still PLANNED — the surface test covers routes, not reason codes, and
[`testing.md`](testing.md) says so rather than implying otherwise.
