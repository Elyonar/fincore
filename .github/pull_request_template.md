## What and why

<!-- What changes, and what problem it solves. Link the issue if there is one. -->

## Checklist

Delete rows that genuinely do not apply — do not tick a box that is not true.

- [ ] I read the service's `docs/design.md` and this change agrees with it.
- [ ] `./mvnw verify` passes locally.
- [ ] Money values are integer minor units — no `float`, `double`, or decimal
      type touches a money value.
- [ ] Ledger corrections are reversing entries; nothing `UPDATE`s or `DELETE`s
      an entry.
- [ ] Every new query is tenant-scoped, with tenant context set via `SET LOCAL`
      inside the transaction.
- [ ] No cross-service imports; no synchronous outbound call added to the
      ledger; no event consumer added to the ledger.
- [ ] Money-touching changes ship with their tests, and the PR names the
      invariants they affect.

## If this changes an AGREED design

- [ ] `docs/CHANGELOG.md` has an entry, and the version is bumped in every
      status header for that service
      (see [`docs/conventions/design-changes.md`](../docs/conventions/design-changes.md)).
- [ ] This PR contains **only** the design change — the implementation follows
      in a separate PR.

## AI assistance

This project is built AI-assisted and human-decided; using an AI tool is
expected, not a problem. Say what you used and what you verified yourself —
review is calibrated by what has actually been checked, not by who typed it.

<!-- e.g. "Claude Code wrote the first draft; I wrote the concurrency test and
     confirmed it fails without the fix." -->
