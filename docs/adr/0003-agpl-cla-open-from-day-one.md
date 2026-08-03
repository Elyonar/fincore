# ADR 0003 — AGPL-3.0-only + CLA, open source from day one

**Status:** Accepted · **Date:** 2026-08-03
**Supersedes:** PRD §11's closed-until-stable / BSL-at-release posture.

## Context

PRD §11 prescribed closed source until ~Month 9–12, then BSL-class
source-available with commercial dual licensing. The founding decision changed:
the project builds trust and seeks funding as an open-source project from the
first commit (build-in-public on YouTube, grant/DFI/digital-public-goods
eligibility, community contribution).

BSL is not an OSI-approved license and generally fails digital-public-goods and
grant eligibility. Apache-2.0 maximizes adoption but surrenders the commercial
dual-license lever and allows hosted resale by third parties with no obligation.

## Decision

- Platform code: **AGPL-3.0-only**, public from day one.
- SDKs and client libraries: **Apache-2.0** (adoption over protection at the
  edges).
- **CLA required from the very first external contribution** (bot-enforced),
  assigning contribution rights to the maintainers. This preserves the ability
  to offer commercial licenses (self-hosters whose lawyers reject AGPL,
  integrators/resellers, embedders) — the PRD §11.4 revenue lines survive under
  dual licensing.

## Consequences

- Grant/DPG-eligible; trust argument ("audit the ledger yourself") available
  immediately.
- AGPL deters free-riding cloud hosts; institutions wanting non-AGPL terms buy a
  commercial license — that is the business model, not a bug.
- The public-repo maintenance tax (issues, PRs, community) starts now; budget
  for it.
- PRD §11 must be revised to match this ADR.
