#!/usr/bin/env bash
# A restore drill for the ledger, end to end.
#
# architecture.md commits to RPO = 0 for acknowledged commits and quarterly restore drills, and
# testing.md marked the drill DEFERRED with no automation behind it. This is the automation: it
# takes a backup, destroys the database, restores it, and then asks the ledger's own invariants
# whether the restored state is sound. A backup nobody has restored is a hope, not a backup.
#
#   ./scripts/restore-drill.sh
#
# Destructive by design — it drops and recreates the ledger database. Local stack only.
set -euo pipefail

DB=ledger
BACKUP=$(mktemp -t fincore-restore-drill)
trap 'rm -f "$BACKUP"' EXIT

psql() { docker compose exec -T postgres psql -U fincore -v ON_ERROR_STOP=1 "$@"; }

say() { printf '\n  %s\n' "$*"; }

# What a restore drill can and cannot prove.
#
# It proves the restore is *faithful*: everything that was there comes back, and every invariant
# holds exactly as well afterwards as it did before. It cannot prove the data was sound to begin
# with, and asserting that would make the drill fail for reasons that have nothing to do with
# backups. The first version of this script did exactly that and reported a scary failure against a
# development database whose own test suite inserts deliberately malformed rows by raw SQL to prove
# the schema rejects them.
#
# So every measurement below is taken twice and compared.

invariant_unbalanced() {
  psql -d "$DB" -tAc "
    SELECT count(*) FROM (
      SELECT tenant_id, currency
        FROM entries GROUP BY tenant_id, currency
       HAVING sum(CASE WHEN direction='DEBIT'  THEN amount_minor ELSE 0 END)
           <> sum(CASE WHEN direction='CREDIT' THEN amount_minor ELSE 0 END)) drift;"
}

invariant_drifted() {
  psql -d "$DB" -tAc "
    SELECT count(*) FROM balances b
     WHERE b.current_minor <> COALESCE((
       SELECT sum(CASE WHEN e.direction='CREDIT' THEN e.amount_minor ELSE -e.amount_minor END)
         FROM entries e WHERE e.account_id = b.account_id), 0);"
}

say "1/5  counting what exists before the drill"
BEFORE_TX=$(psql -d "$DB" -tAc "SELECT count(*) FROM ledger_transactions;")
BEFORE_ENTRIES=$(psql -d "$DB" -tAc "SELECT count(*) FROM entries;")
BEFORE_SUM=$(psql -d "$DB" -tAc "SELECT COALESCE(sum(amount_minor),0) FROM entries;")
BEFORE_UNBALANCED=$(invariant_unbalanced)
BEFORE_DRIFTED=$(invariant_drifted)
echo "        transactions=$BEFORE_TX entries=$BEFORE_ENTRIES sum=$BEFORE_SUM"
echo "        invariants: unbalanced=$BEFORE_UNBALANCED drifted=$BEFORE_DRIFTED (the baseline to match)"

say "2/5  taking a backup"
docker compose exec -T postgres pg_dump -U fincore -Fc "$DB" > "$BACKUP"
echo "        $(wc -c < "$BACKUP") bytes"

say "3/5  destroying the database (this is the part nobody rehearses)"
docker compose stop ledger core >/dev/null 2>&1 || true
psql -d postgres -c "DROP DATABASE $DB WITH (FORCE);" >/dev/null
psql -d postgres -c "CREATE DATABASE $DB OWNER fincore;" >/dev/null

say "4/5  restoring"
docker compose exec -T postgres pg_restore -U fincore -d "$DB" --no-owner < "$BACKUP" >/dev/null 2>&1 || true

AFTER_TX=$(psql -d "$DB" -tAc "SELECT count(*) FROM ledger_transactions;")
AFTER_ENTRIES=$(psql -d "$DB" -tAc "SELECT count(*) FROM entries;")
AFTER_SUM=$(psql -d "$DB" -tAc "SELECT COALESCE(sum(amount_minor),0) FROM entries;")
echo "        transactions=$AFTER_TX entries=$AFTER_ENTRIES sum=$AFTER_SUM"

say "5/5  asking whether the invariants hold exactly as well as they did before"
AFTER_UNBALANCED=$(invariant_unbalanced)
AFTER_DRIFTED=$(invariant_drifted)
echo "        invariants: unbalanced=$AFTER_UNBALANCED drifted=$AFTER_DRIFTED"

echo
if [ "$BEFORE_TX" = "$AFTER_TX" ] && [ "$BEFORE_ENTRIES" = "$AFTER_ENTRIES" ] \
   && [ "$BEFORE_SUM" = "$AFTER_SUM" ] \
   && [ "$BEFORE_UNBALANCED" = "$AFTER_UNBALANCED" ] && [ "$BEFORE_DRIFTED" = "$AFTER_DRIFTED" ]; then
  echo "  RESTORE DRILL PASSED"
  echo "    every transaction and entry came back, the entry sum is identical to the digit,"
  echo "    and the invariants hold exactly as well after the restore as before it."
  if [ "$BEFORE_UNBALANCED" != "0" ] || [ "$BEFORE_DRIFTED" != "0" ]; then
    echo
    echo "    Note: the baseline was not clean ($BEFORE_UNBALANCED unbalanced, $BEFORE_DRIFTED drifted)."
    echo "    On a development database that is expected — the ledger's own suite inserts"
    echo "    malformed rows by raw SQL to prove the schema rejects them. Against production data"
    echo "    a non-zero baseline is itself the finding, and this drill is not what would catch it:"
    echo "    the hourly invariant run is."
  fi
  STATUS=0
else
  echo "  RESTORE DRILL FAILED — the restore was not faithful"
  echo "    transactions $BEFORE_TX -> $AFTER_TX, entries $BEFORE_ENTRIES -> $AFTER_ENTRIES,"
  echo "    sum $BEFORE_SUM -> $AFTER_SUM,"
  echo "    unbalanced $BEFORE_UNBALANCED -> $AFTER_UNBALANCED, drifted $BEFORE_DRIFTED -> $AFTER_DRIFTED"
  STATUS=1
fi

docker compose start ledger core >/dev/null 2>&1 || true
echo
exit $STATUS
