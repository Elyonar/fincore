# Customer — Design Changelog

Amendments to the **agreed** Customer design. Process and entry format:
[`docs/conventions/design-changes.md`](../../../docs/conventions/design-changes.md).

The version here matches the status header in every Customer design doc. Newest
entry first.

---

## [1.0.0] — 2026-08-13 · MAJOR

**This service's design becomes its own.** Customer was designed and built as a
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
  process was built to prevent. It matters more here than anywhere: this is the
  only schema holding personal data, and the documents describing what happens to
  it should be findable from the service that holds it.
- **What did not change:** any decision. v1.0 is the design as extracted. The
  *argument* for the domain rules remains Core's design.md §Customer, cited
  rather than copied.
- **Scope note:** the packaging decision itself is
  [ADR 0020](../../../docs/adr/0020-customer-and-product-become-deployables.md).
  ADRs are platform law; this changelog is service history.

### Recorded as inherited, not new

Written down here for the first time, having previously been carried only in
Core's module documentation and in code comments:

- not-found and wrong-tenant answer identically, because distinguishing them
  confirms that a person banks somewhere;
- a KYC tier change requires a reason and is append-only **by trigger**, as is
  every consent decision;
- absent consent is granted for every category except `MARKETING`;
- `external_ref` blank draws from the institution's own numbering series, rather
  than being taken verbatim from the request;
- `customer_accounts` carries account ownership, and Core reads it across the
  boundary as a **security control** — the money path resolves the governing
  product from the account so a caller cannot name the rules that judge its own
  transaction.

### Gaps recorded rather than discovered

Three absences are now stated in [`design.md`](design.md) instead of being
inferred from missing routes: there is **no contact-update endpoint**, contact
details are **stored in plaintext** while Notification encrypts its own copy of
the same address, and there is **no erasure or offboarding path** — the last
being an open design question rather than unbuilt work, because what erasure
means against an immutable ledger is unanswered.

### Open obligations from ADR 0020

- **The ArchUnit boundary rule now exists** (`BoundaryTest`), closing obligation 3
  for this service.
- Both eligibility reads remain **uncacheable by design**, and their
  per-transaction cost remains unmeasured.
