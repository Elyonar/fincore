# docs/ — cross-cutting documentation only

This folder holds material that spans the whole platform:

- [`prd.md`](prd.md) — **the PRD, the platform's source of truth** ("sacred
  guide"): vision, constitution, service decomposition, phases, licensing,
  risks. Changes come as PRs that bump its version header.
- [`adr/`](adr/) — Architecture Decision Records (numbered, immutable).
  Where an accepted ADR conflicts with an older PRD section, the ADR wins
  until the PRD is revised.
- [`conventions/`](conventions/) — standing conventions: commit format
  ([`commits.md`](conventions/commits.md)), how an agreed service design is
  amended ([`design-changes.md`](conventions/design-changes.md)), and future
  coding/review conventions as they're agreed.

**Service-specific documentation does not live here.** Every service carries
its own `README.md` and, when it grows, its own `docs/` folder:

```
services/<name>/README.md        # the service's map: purpose, boundaries, doc index
services/<name>/docs/design.md   # agreed design (DRAFT until marked AGREED)
services/<name>/docs/CHANGELOG.md# amendments once AGREED — the design's history
services/<name>/docs/<topic>.md  # deeper topics, indexed from the service README
```

Once a service's design is AGREED it carries a version, and every change to it
is an amendment in that service's `CHANGELOG.md` — see
[`conventions/design-changes.md`](conventions/design-changes.md). ADRs remain
for cross-cutting decisions; a service's own contract history belongs to the
service.

This keeps the monorepo root clean and gives every service a self-contained
"memory map" — a human or AI agent can load exactly the context it needs by
reading `AGENTS.md` → the service README → the referenced docs, and nothing
else.
