# Product — Design Changelog

Amendments to the **agreed** Product design. Process and entry format:
[`docs/conventions/design-changes.md`](../../../docs/conventions/design-changes.md).

The version here matches the status header in every Product design doc. Newest
entry first.

---

## [1.0.0] — 2026-08-13 · MAJOR

**This service's design becomes its own.** Product was designed and built as a
module inside Core, and its agreed contract lived in
[`services/core/docs/design.md`](../../core/docs/design.md). ADR 0020 made it a
deployable and left the documents where they were — which meant an amendment to
*this* service's contract had to be recorded in *another* service's changelog,
where nobody looking here would find it.

- **Docs:** `design.md`, `api.md`, `data-model.md`, `testing.md` (new); this file
- **Why:** the convention exists so that code contradicting an agreed doc is a
  bug rather than a discovery. A deployable whose agreed doc lives elsewhere
  cannot honour it — the doc is amended under someone else's version number, on
  someone else's release rhythm, and the two drift in the one direction the
  process was built to prevent.
- **What did not change:** any decision. v1.0 is the design as extracted. The
  *argument* for the domain rules — why a published version is immutable, why a
  refusal is an answer, why percentages are basis points — remains Core's
  design.md §Product, cited rather than copied. Copying it would have created the
  second source of truth this entry exists to remove.
- **Scope note:** the packaging decision itself is
  [ADR 0020](../../../docs/adr/0020-customer-and-product-become-deployables.md)
  and is not restated here. ADRs are platform law; this changelog is service
  history.

### Recorded as inherited, not new

Four things were true before the extraction and are written down here for the
first time, because they were previously carried in Core's module documentation
and in code comments:

- a published version and its rules are immutable **by trigger**, insert as well
  as update, so a rule cannot be slid in after the checker read the version;
- the publisher of a version may not be its author, enforced by the row;
- a decision refusal is a `200` carrying a reason, never a `4xx`;
- a currency with no fee rule is `CURRENCY_MISMATCH`, never free.

### Open obligations from ADR 0020

- **The decision cache is not built.** Deliberate: adding it in the same change
  as the extraction would have meant debugging two new things at once. The key is
  settled — `(productId, version)`, never invalidated by time.
- **The ArchUnit boundary rule now exists** (`BoundaryTest`), closing obligation 3
  for this service.
