# Convention — changing an AGREED service design

A service's design docs are a **contract**, not notes. Once marked AGREED they
carry a version, and every change to them is an amendment recorded in that
service's `docs/CHANGELOG.md`.

This exists because of the rule in `AGENTS.md`: *code that contradicts an
agreed design is wrong even if it works*. That rule is only enforceable if
"what was agreed, and when" has an answer.

## One contract, one version

A service's design is versioned **as a whole**, not per file. `data-model.md`,
`api.md`, and the rest describe one system; a schema change that doesn't move
the API still changes the contract a reader is holding. Every design doc in the
service carries the same version in its status header:

```
**Status:** AGREED vX.Y (date) — amendments in [`CHANGELOG.md`](CHANGELOG.md)
```

## Versioning

| Bump | When | Caller consequence |
|---|---|---|
| **MAJOR** | A guarantee or contract weakens or changes meaning — an invariant relaxes, an endpoint or field changes semantics, an error code's meaning shifts, an operational commitment (RPO, retention) is reduced | Callers **must** act |
| **MINOR** | Capability is added, or a guarantee is tightened, backward compatibly — new endpoint, new field, new invariant, stricter validation | Callers may ignore |
| **PATCH** | Clarification with no behavioural change — a rule made explicit that was already true | None |

Typos, formatting, and rewording that doesn't change meaning need **no entry
and no bump**. If you cannot tell whether the meaning changed, it changed.

## What requires an amendment

Anything that changes what an implementer or caller would build: schema,
API surface, error catalog, invariants, algorithms, concurrency protocol,
guarantees, and operational commitments.

## Rules

1. **Design lands before code, in its own PR.** An amendment PR may not also
   change implementation. Otherwise the doc becomes a description of whatever
   was written, which is the failure this convention exists to prevent.
2. **Code contradicting an AGREED design is a bug.** Fix the code, or land an
   amendment first. Never fix the discrepancy by quietly editing the doc.
3. **Every amendment PR carries three things:** the doc edits, the CHANGELOG
   entry, and the version bump in every status header for that service.
4. **Superseded decisions are marked, never deleted.** The decision log in
   `design.md` is append-and-annotate — a replaced decision gets a
   *Superseded by vX.Y* note and stays. Same discipline as ADRs.
5. **Weakening an invariant requires its test to change in the same PR as the
   code**, and the changelog entry must name the test. An invariant nobody
   tests is a comment.
6. **Cross-service impact needs an ADR as well.** The boundary: if only this
   service's contract changes, a CHANGELOG entry is enough. If another service
   must change to stay correct, raise an ADR *and* record a CHANGELOG entry in
   each affected service. The ADR holds the decision; the changelogs hold the
   consequences.

## Entry format

```markdown
## [1.1.0] — 2026-08-12 · MINOR

**Multi-shot hold capture.**

- **Docs:** `data-model.md`, `api.md`, `posting-algorithm.md`
- **Why:** card acquiring settles one authorization across several captures;
  single-shot forced orchestration to re-authorize, which loses the customer's
  reservation between attempts.
- **Impact:** backward compatible — existing single-capture callers unaffected.
- **Supersedes:** "capture is single-shot" in the design.md decision log.
- **Tests:** `HoldMultiCaptureTest`; invariant 3 extended to partial captures.
- **Migration:** `V7__holds_captured_total.sql`
```

Newest entry first. The version and date in the heading must match the status
headers in that service's docs.

## Why not ADRs for this

ADRs record **cross-cutting** decisions — choices that constrain the whole
platform, like the language, the licence, or build order. A service's internal
contract changes far more often and concerns a narrower audience. Mixing the
two would either flood the ADR log with ledger schema detail or bury real
platform decisions. The split keeps each useful: **ADRs are platform law,
changelogs are service history.**
