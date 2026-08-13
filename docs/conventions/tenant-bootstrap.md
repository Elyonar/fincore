# Convention — how a tenant comes into existence

**Status:** DRAFT v1.1 (2026-08-08) · recorded by
[ADR 0016](../adr/0016-tenant-bootstrap-manifest.md)

One manifest declares every institution this deployment serves. Each participant
reads it and converges to it. Nothing coordinates, so nothing half-coordinates.

This is the *only* sanctioned path. `scripts/provision-tenant.sh` is deleted with
the implementation of this convention, and no other mechanism may create a
tenant.

---

## 1. The manifest

[`bootstrap/tenants.json`](../../bootstrap/tenants.json) — the single source of
truth for which tenants exist.

**JSON rather than YAML**, deliberately: the bootstrap scripts parse it with
`python3` and the standard library only, which is the same dependency-free posture
the repo's existing scripts already take. A manifest that needs a package
installed before a deployment can read it is a manifest that fails on the machine
that matters.

```json
{
  "version": 1,
  "tenants": [
    {
      "id": "8f1c0a52-3d47-4e19-9b6a-2c7f4a10d8e3",
      "realm": "acme-mfb",
      "legalName": "Acme Microfinance Bank Limited",
      "displayName": "Acme MFB",
      "countryCode": "NG",
      "segment": "MFB",
      "businessTimezone": "Africa/Lagos",
      "webOrigin": "http://localhost:3000",
      "superAdmin": {
        "username": "ada.admin",
        "email": "ada.admin@acme.example",
        "firstName": "Ada",
        "lastName": "Okonkwo"
      }
    }
  ]
}
```

Every field is required. `segment` is one of `MFB`, `COOPERATIVE`, `PSB`,
`FINTECH`, `OTHER`.

### One administrator, and no other staff

The manifest seeds **exactly one super-administrator per tenant** and stops.

An earlier draft allowed a `staff` list. It is removed: seeding a teller and a
supervisor makes a demo easier and makes the product wrong, because it implies
the manifest is where an institution's people are managed. They are not. The
super-administrator creates staff through the administration surface
([`admin-surface.md`](../../services/core/docs/admin-surface.md) §5), and until
that exists a tenant simply has one user — which is also the smallest thing to
test.

### `id` is chosen once and never changes

It is the `tenant_id` claim in every token, the primary key in three registries,
and the value row-level security scopes on. Changing it does not rename a tenant;
it creates a second one and orphans the first.

### Secrets are never in the manifest

There is no password field and no secret field, and the seeder **refuses a
manifest that contains one**. The administrator's temporary password is
generated at seed time, written once to `bootstrap/.seeded-credentials.txt`
(mode 600, gitignored), and never written anywhere else. `AGENTS.md` hard
rule 8: the manifest is committed and reviewed, so anything secret in it is a
secret in the repository.

## 2. What gets written, and what deliberately does not

Three rows per tenant — one per deployable — in the three tenant registries, and
nowhere else.

| Participant | Table | Columns written |
|---|---|---|
| Ledger | `tenants` | `id`, `name` |
| Core | `platform.tenants` | `id`, `name`, `business_timezone` |
| Notification | `notification.tenants` | `id`, `name` |
| Identity provider | one realm per entry | identifiers, origin, the super-administrator |

**This is provisioning data, not test data.** A tenant absent from these
registries is refused by `TenantGate` with a bodiless 404 on every request, so a
row here is the minimum a tenant needs in order to exist.

**Nothing else is seeded.** No customers, no products, no accounts, no tills, no
approval tiers, no sample money. The institution's own administrator creates all
of it. That restraint is the point of seeding one administrator and stopping: a
system that arrives pre-populated teaches nobody how it is configured, and demo
rows in a money system outlive the demo.

**The `*_test` databases are untouched.** The suites own those and seed their own
tenants through `TenantRegistry.register` in test code. No bootstrap script
connects to them.

`business_timezone` is applied by Core alone, because
`platform.tenants.business_timezone` is Core's column — the ledger and
notification registries have no such concept and must not grow one.

## 3. Rules

1. **Additive only.** Adding an entry provisions. Removing one does nothing.
   Deprovisioning is not available through this path in any form.
2. **Validate the whole manifest before writing anything.** A renderer that emits
   the valid half of a broken list has produced a partially-provisioned tenant,
   which is the failure this convention exists to remove. The renderer checks
   every entry — required fields, UUID form, known segment, duplicate ids and
   realms, and the absence of literal secrets — before it writes a single file.
3. **Refuse on a malformed manifest**, loudly, naming the entry and the field.
4. **Seeding is idempotent.** `ON CONFLICT (id) DO NOTHING` everywhere; re-running
   after adding a tenant adds only the new one.
5. **One manifest per deployment.** Two copies drift, and the drift shows up as a
   tenant that authenticates and 404s.
6. **The administrator's credential is temporary and must be changed on first
   use.** A seeded password that survives first login is a shared credential in a
   configuration file.

## 4. Running it

Two phases, because the two halves become ready at different times.

```bash
docker compose up -d                                # 1. identity seeds the manifest; services run Flyway
bootstrap/seed-registries.sh                        # 2. once the tables exist
```

> **ADR 0018 amendment.** The realm half of this procedure is retired with
> Keycloak. `render-realms.sh` and `keycloak/realm-template.json` no longer
> exist; the identity service's own `ManifestSeeder` reads the same manifest at
> every startup, registers the tenant, and seeds the super-administrator with a
> temporary credential (written once, mode 600, to
> `bootstrap/.seeded-credentials.txt`). The paragraphs below describing realm
> rendering and import-only-on-absence are kept for the record of *why* the
> control plane (ADR 0016) exists, but they describe the retired mechanism.

**Why the split.** `db/init/` cannot register tenants: its scripts run when the
PostgreSQL volume is first created, before any service has started, so the tables
Flyway owns do not exist yet — `db/init/20-dev-tenant.sql` records exactly this.
The registries can only be written once the services have migrated.

### Retired: how this worked under Keycloak

Kept for the record of *why* ADR 0016 exists, and written in the past tense
because none of it runs any more.

`render-realms.sh` **wrote** one `<realm>-realm.json` per tenant into
`keycloak/import/`, substituting the entry's identifiers and origin into
`keycloak/realm-template.json` and appending the super-administrator with
`job:admin` and a forced password change.

**Import semantics decided the operational story, and they were the sharpest
edge in that design.** The provider imported realms that were absent and left
existing realms alone. Adding a tenant and restarting provisioned it; editing an
*existing* tenant in the manifest did **not** take effect, because the realm
already existed and was never overwritten. That asymmetry is what stopped a
redeploy from destroying a tenant's authored roles — and it is one of ADR 0016's
triggers for building a control plane rather than living with it.

The first-party identity service has no such asymmetry: `ManifestSeeder` reads
the manifest on every boot and converges to it, which is the property this
section was written wishing for.

## 5. Adding an institution

1. Add an entry to `bootstrap/tenants.json` with a freshly generated UUID.
2. Open a pull request. The manifest is a reviewed artefact; that review is the
   control that stands in for an audit trail.
3. Deploy. The identity service seeds the tenant and its super-administrator at
   startup; run `bootstrap/seed-registries.sh` once the services are up to
   register it with the five deployables that gate on a registry.
4. Hand the administrator their temporary credential from
   `bootstrap/.seeded-credentials.txt` (written once, mode 600, gitignored).
   They sign in, are required to change it and to give a contact number, and
   land in setup.

## 6. What the super-administrator can do on arrival

Stated here because it is the thing most likely to be assumed rather than
checked. ADR 0016 delivers a login; what follows is how an institution gets from
there to taking its first deposit.

**Everything needed to transact is reachable**, and the portal walks it in
order: organizational units and branches; staff, with roles composed from the
platform's permission vocabulary (ADR 0017); the institution's own internal
accounts; a product, its fee and limit rules, and publishing a version; and a
till to take cash through. Account opening composes a ledger account, a product
check and a customer record in one call.

**What is still not reachable**, and each is a deliberate omission rather than
an oversight:

1. **Deactivating a member of staff.** It needs a second administrator's
   signature and that approval flow does not exist yet, so the control is not
   offered rather than offered and ignored.
2. **Anything a customer receives.** Notification's senders are log adapters
   until the messaging connector exists, so a message is queued, recorded, and
   delivered nowhere.
3. **Inter-bank movement.** No rails connector, so money moves only between
   accounts within one institution.

Roles seeded here are a starting position, not a permanent structure: under
[ADR 0017](../adr/0017-tenant-defined-roles.md) nothing seeded is privileged, and
the super-administrator may rename, recompose or delete any of it. **The manifest
is where an institution begins, not what it is.**
