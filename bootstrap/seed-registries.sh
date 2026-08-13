#!/usr/bin/env bash
#
# Register every tenant in the manifest with each deployable that gates on a registry.
#
# Recorded by ADR 0016; the rules are docs/conventions/tenant-bootstrap.md. Run this AFTER the
# services are up — Flyway owns these tables and creates them at service startup, which is why
# db/init/ cannot do this job (its scripts run when the PostgreSQL volume is first created, before
# any service has started; db/init/20-dev-tenant.sql says so).
#
# WHAT THIS WRITES, AND WHAT IT DELIBERATELY DOES NOT.
#
# Five rows per tenant, one per deployable that gates on a registry, and nowhere else:
#
#     ledger        tenants                 (id, name)
#     core          platform.tenants        (id, name, business_timezone)
#     notification  notification.tenants    (id, name)
#     product       product.tenants         (id, name)
#     customer      customer.tenants        (id, name)
#
# Product and customer joined the list when they became deployables of their own (ADR 0020).
# Identity is absent on purpose: it has no tenant gate to pass, because it is the thing that
# establishes which tenant a caller belongs to.
#
# That is provisioning data, not test data: a tenant absent from these registries is refused by
# TenantGate with a bodiless 404 on every request, so a row here is the minimum a tenant needs to
# exist at all. Nothing else is written — no customers, no products, no accounts, no tills, no
# sample money. The institution's own administrator creates all of that, which is the whole point
# of seeding one super-administrator and stopping.
#
# The *_test databases are untouched. The suites own those and seed their own tenants through
# TenantRegistry.register in test code; this script never connects to them.
#
# Idempotent: ON CONFLICT DO NOTHING everywhere, so re-running after adding a tenant to the
# manifest adds only the new one. Removing a tenant from the manifest does NOTHING — seeding is
# additive, and deprovisioning is a deliberate act that is not available here.
#
# This script is the interim path. ADR 0016's TenantSeeder does the same work inside each service
# at startup, which is where it belongs; until that is built, this is the sanctioned way.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
MANIFEST="${MANIFEST:-$HERE/tenants.json}"
PSQL_USER="${PSQL_USER:-fincore}"

[ -f "$MANIFEST" ] || { echo "ERROR: manifest not found: $MANIFEST" >&2; exit 1; }

# One psql invocation per database, fed on stdin through the compose service.
run_sql() {
  local db="$1"
  docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -q -U "$PSQL_USER" -d "$db"
}

echo "==> seeding tenant registries from $(basename "$MANIFEST")"

SQL_LEDGER=$(python3 -c '
import json,sys
for t in json.load(open(sys.argv[1]))["tenants"]:
    print("INSERT INTO tenants (id, name, created_by) VALUES (%s, %s, %s) ON CONFLICT (id) DO NOTHING;"
          % ("\x27"+t["id"]+"\x27", "\x27"+t["displayName"].replace("\x27","\x27\x27")+"\x27", "\x27bootstrap:manifest\x27"))
' "$MANIFEST")

SQL_CORE=$(python3 -c '
import json,sys
for t in json.load(open(sys.argv[1]))["tenants"]:
    print("INSERT INTO platform.tenants (id, name, created_by, business_timezone) VALUES (%s, %s, %s, %s) ON CONFLICT (id) DO NOTHING;"
          % ("\x27"+t["id"]+"\x27", "\x27"+t["displayName"].replace("\x27","\x27\x27")+"\x27",
             "\x27bootstrap:manifest\x27", "\x27"+t["businessTimezone"]+"\x27"))
' "$MANIFEST")

SQL_NOTIF=$(python3 -c '
import json,sys
for t in json.load(open(sys.argv[1]))["tenants"]:
    print("INSERT INTO notification.tenants (id, name, created_by) VALUES (%s, %s, %s) ON CONFLICT (id) DO NOTHING;"
          % ("\x27"+t["id"]+"\x27", "\x27"+t["displayName"].replace("\x27","\x27\x27")+"\x27", "\x27bootstrap:manifest\x27"))
' "$MANIFEST")

SQL_PRODUCT=$(python3 -c '
import json,sys
for t in json.load(open(sys.argv[1]))["tenants"]:
    print("INSERT INTO product.tenants (id, name, created_by) VALUES (%s, %s, %s) ON CONFLICT (id) DO NOTHING;"
          % ("\x27"+t["id"]+"\x27", "\x27"+t["displayName"].replace("\x27","\x27\x27")+"\x27", "\x27bootstrap:manifest\x27"))
' "$MANIFEST")

SQL_CUSTOMER=$(python3 -c '
import json,sys
for t in json.load(open(sys.argv[1]))["tenants"]:
    print("INSERT INTO customer.tenants (id, name, created_by) VALUES (%s, %s, %s) ON CONFLICT (id) DO NOTHING;"
          % ("\x27"+t["id"]+"\x27", "\x27"+t["displayName"].replace("\x27","\x27\x27")+"\x27", "\x27bootstrap:manifest\x27"))
' "$MANIFEST")

echo "    ledger"        && echo "$SQL_LEDGER"   | run_sql ledger
echo "    core"          && echo "$SQL_CORE"     | run_sql core
echo "    notification"  && echo "$SQL_NOTIF"    | run_sql notification
echo "    product"       && echo "$SQL_PRODUCT"  | run_sql product
echo "    customer"      && echo "$SQL_CUSTOMER" | run_sql customer

echo
echo "==> registered:"
docker compose exec -T postgres psql -qtA -U "$PSQL_USER" -d core \
  -c "SELECT '    ' || name || '  ' || id || '  ' || business_timezone || '  ' || status FROM platform.tenants ORDER BY created_at;"

echo
echo "==> cross-check (all five registries must agree)"
for db in ledger core notification product customer; do
  tbl=tenants
  [ "$db" = core ] && tbl=platform.tenants
  [ "$db" = notification ] && tbl=notification.tenants
  [ "$db" = product ] && tbl=product.tenants
  [ "$db" = customer ] && tbl=customer.tenants
  n=$(docker compose exec -T postgres psql -qtA -U "$PSQL_USER" -d "$db" -c "SELECT count(*) FROM $tbl;")
  printf '    %-13s %s\n' "$db" "$n"
done
echo
echo "    Equal counts is the check that matters. A tenant registered in one and not the others"
echo "    authenticates correctly and then 404s — the defect this whole design exists to remove."
