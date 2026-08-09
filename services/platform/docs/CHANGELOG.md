# Platform — Changelog

Amendments to the AGREED design, newest first. Format and versioning rules:
[`design-changes.md`](../../../docs/conventions/design-changes.md).

The design is **DRAFT** until reviewed and marked AGREED. No domain code lands
while it is DRAFT, per the scaffold. The first entry below is written for the
moment it is agreed, and carries no amendment because the initial design is not
one.

---

## [1.0.0] — unreleased · initial design

**The control plane.**

- **Docs:** `design.md`, `data-model.md`, `api.md`, `provisioning-protocol.md`,
  `console.md`, `testing.md`, `README.md`
- **Why:** ADR 0010 made tenant provisioning a multi-part operation and recorded
  that it "must fail loudly rather than half-succeed". What shipped was
  `scripts/provision-tenant.sh`, which writes one of the three registries a
  tenant needs — so a tenant provisioned by the sanctioned path is refused by
  Core on every request. No client application can be built for a tenant that
  cannot be created.
- **Decision:** [ADR 0015](../../../docs/adr/0015-control-plane-and-tenant-provisioning.md)
  — a separate deployable, participants asked rather than written to, and
  provisioning as a saga using the money path's three-valued outcome protocol.
- **Impact:** new deployable; three services gain an internal registration
  surface (their own CHANGELOG entries); `scripts/provision-tenant.sh` is
  superseded and deleted.
- **Tests:** none yet — every suite in `testing.md` is PLANNED. The marker moves
  when the tests exist.

---

## Amendments this design requires elsewhere

Recorded here for the implementation PR to carry into each service's own
CHANGELOG, per `design-changes.md` rule 6. Each is MINOR: a capability is added
and no existing guarantee moves.

**Ledger — internal tenant registration.**
`POST`/`DELETE /internal/v1/tenants`, accepting a verified peer service that
carries no tenant claim; `platform` added to
`fincore.ledger.auth.trusted-callers`. The tenant registry gains a caller that
is not a test or the development seeder for the first time. `TenantRegistry`'s
javadoc — *"never reachable from a request path"* — is amended to state the rule
it was actually protecting: never reachable by a caller who could name their own
tenant. A peer service under mutual authentication is not that caller, and the
suite in `testing.md` §6 proves a tenant user token still cannot reach it.

**Core — the same surface, plus a resolver.**
`/internal/**` is excluded from the user-facing identity filter and handled by a
service-caller resolver, so a tenant-less peer authenticates without the user
path ever accepting one. `TenantGate` must not run on this prefix: the
endpoint's purpose is that the tenant does not exist yet, and a gate that 404s
unknown tenants would refuse the call that makes them known.

**Notification — the same surface, same reasoning.**

**`keycloak/` — a platform realm.**
Operator users, the seven permissions and two job composites in `api.md` §2.
The tenant realm template is unchanged: its permission vocabulary is already
complete, and the control plane adds nothing inside a tenant.

**`scripts/` — `provision-tenant.sh` deleted.**
Two divergent paths to the same state means one is wrong and nobody knows which.
