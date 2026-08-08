#!/usr/bin/env bash
#
# Provision a tenant: a Keycloak realm from the template, and a row in the platform tenant
# registry — as one operation that fails loudly rather than half-succeeds (ADR 0010's
# consequence, made executable; scheduled by ADR 0014). If the registry insert fails, the realm
# is rolled back. Neither alone makes a usable tenant.
#
# Usage:
#   scripts/provision-tenant.sh <realm-name> <display-name> [tenant-uuid]
#
# Environment (defaults are the compose stack's):
#   KEYCLOAK_URL        http://localhost:8180
#   KEYCLOAK_ADMIN      admin
#   KEYCLOAK_ADMIN_PASSWORD  admin
#   WEB_ORIGIN          http://localhost:5173          # the SPA's origin (fincore-web client)
#   CORE_CLIENT_SECRET  generated when unset            # core's client-credentials secret
#   PGURL               postgresql://fincore:fincore@localhost:55432/ledger
#   REGISTRY_TABLE      tenants                         # the platform tenant registry table
#
# The tenant UUID defaults to a fresh one; pass it explicitly when re-provisioning a known
# tenant. The script is idempotent per realm name: an existing realm is an error, not an
# overwrite — deleting a tenant's identity must never be a side effect of a typo.

set -euo pipefail

REALM="${1:?realm name required (e.g. acme-mfb)}"
DISPLAY="${2:?display name required (e.g. 'Acme Microfinance Bank')}"
TENANT_ID="${3:-$(uuidgen | tr '[:upper:]' '[:lower:]')}"

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8180}"
KEYCLOAK_ADMIN="${KEYCLOAK_ADMIN:-admin}"
KEYCLOAK_ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
WEB_ORIGIN="${WEB_ORIGIN:-http://localhost:5173}"
CORE_CLIENT_SECRET="${CORE_CLIENT_SECRET:-$(uuidgen | tr -d -)}"
PGURL="${PGURL:-postgresql://fincore:fincore@localhost:55432/ledger}"
REGISTRY_TABLE="${REGISTRY_TABLE:-tenants}"

HERE="$(cd "$(dirname "$0")/.." && pwd)"
TEMPLATE="$HERE/keycloak/realm-template.json"
[ -f "$TEMPLATE" ] || { echo "ERROR: $TEMPLATE not found" >&2; exit 1; }

echo "==> provisioning tenant '$REALM' (tenant_id=$TENANT_ID)"

# --- 1. Admin token -----------------------------------------------------------
TOKEN=$(curl -fsS "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=admin-cli \
  -d "username=$KEYCLOAK_ADMIN" -d "password=$KEYCLOAK_ADMIN_PASSWORD" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])') \
  || { echo "ERROR: could not authenticate against Keycloak at $KEYCLOAK_URL" >&2; exit 1; }

# --- 2. Refuse to overwrite ---------------------------------------------------
if curl -fsS -o /dev/null -H "Authorization: Bearer $TOKEN" \
     "$KEYCLOAK_URL/admin/realms/$REALM" 2>/dev/null; then
  echo "ERROR: realm '$REALM' already exists. Deleting identity is never a side effect." >&2
  exit 1
fi

# --- 3. Render the template and create the realm ------------------------------
RENDERED=$(python3 - "$TEMPLATE" <<PY
import json, sys
raw = open(sys.argv[1]).read()
raw = raw.replace("__TENANT_REALM__", "$REALM")
raw = raw.replace("__TENANT_DISPLAY_NAME__", """$DISPLAY""")
raw = raw.replace("__TENANT_ID__", "$TENANT_ID")
raw = raw.replace("__WEB_ORIGIN__", "$WEB_ORIGIN")
raw = raw.replace("__CORE_CLIENT_SECRET__", "$CORE_CLIENT_SECRET")
json.loads(raw)  # refuse to send something malformed
print(raw)
PY
)

curl -fsS -X POST "$KEYCLOAK_URL/admin/realms" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "$RENDERED" \
  || { echo "ERROR: realm creation failed" >&2; exit 1; }
echo "    realm created"

# --- 4. Registry row — and roll the realm back if it fails --------------------
if ! psql "$PGURL" -v ON_ERROR_STOP=1 -q \
     -c "INSERT INTO $REGISTRY_TABLE (id, name, created_by) VALUES ('$TENANT_ID', '$REALM', 'provision-tenant')"; then
  echo "ERROR: tenant registry insert failed — rolling back realm '$REALM'" >&2
  curl -fsS -X DELETE "$KEYCLOAK_URL/admin/realms/$REALM" -H "Authorization: Bearer $TOKEN" \
    || echo "WARNING: rollback failed too; delete realm '$REALM' by hand" >&2
  exit 1
fi
echo "    registry row inserted"

echo "==> tenant '$REALM' provisioned"
echo "    tenant_id:           $TENANT_ID"
echo "    issuer:              $KEYCLOAK_URL/realms/$REALM"
echo "    SPA client:          fincore-web (PKCE, origin $WEB_ORIGIN)"
echo "    core client secret:  $CORE_CLIENT_SECRET   (store it; it will not be shown again)"
