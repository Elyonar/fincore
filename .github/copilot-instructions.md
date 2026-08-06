# GitHub Copilot — instructions for fincore

**Read [`AGENTS.md`](../AGENTS.md) first.** It is the canonical memory map for
this repository: where truth lives, the hard rules, and the documentation
convention. This file exists only so Copilot finds its way there.

Before writing code in a service, read that service's `docs/design.md` and its
`docs/CHANGELOG.md`. A service design marked **AGREED** is a contract: code
that contradicts it is wrong even if it works and even if it passes tests.

The rules most likely to trip up generated code:

1. **Money is integer minor units (kobo).** Never `double`, `float`, or a
   decimal type for a money value. Enforced by ArchUnit in
   `services/ledger/src/test/java/.../architecture/HardRulesTest.java`.
2. **Ledger entries are append-only.** Corrections are reversing entries, never
   `UPDATE` or `DELETE`. Enforced by database triggers.
3. **Every query is tenant-scoped**, and tenant context is set with
   `SET LOCAL` inside the request transaction — never a session-level `SET`,
   because connections are pooled across tenants.
4. **Modules never read each other's tables.** A deployable may hold several
   modules (`core-customer`, `core-product`, `core-orchestration`); each owns a
   schema and is reached only through its published interface. No joins across
   schemas, no shared repositories. Only `core-orchestration` may hold the
   ledger client.
4. **No cross-service imports.** Services integrate over APIs and events only.
5. **The ledger makes no synchronous outbound calls and consumes no events.**
6. **Changing an AGREED design** requires a `CHANGELOG.md` entry and a version
   bump — see [`docs/conventions/design-changes.md`](../docs/conventions/design-changes.md).
   Design changes land in their own PR, before the code.
