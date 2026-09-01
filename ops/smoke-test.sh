#!/usr/bin/env bash
# Smoke test: boot DB + server, wait for "Cosmic is now online", check login port, stop again.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

TIMEOUT_S="${SMOKE_TIMEOUT_S:-180}"

ensure_env
load_env
render_config

log "Starting stack for smoke test..."
compose up -d --build

log "Waiting up to ${TIMEOUT_S}s for '$ONLINE_MARKER'..."
ok=0
for _ in $(seq 1 "$TIMEOUT_S"); do
  # note: grep without -q (and >/dev/null) so docker logs is not SIGPIPE'd under pipefail
  if compose logs "$SERVER_SERVICE" 2>/dev/null | grep "$ONLINE_MARKER" >/dev/null 2>&1; then
    ok=1
    break
  fi
  # fail fast if the server container died
  state="$(compose ps --format json "$SERVER_SERVICE" 2>/dev/null | grep -o '"State":"[a-z]*"' | head -n1 || true)"
  if [[ "$state" == *'"State":"exited"'* || "$state" == *'"State":"dead"'* ]]; then
    warn "Server container exited early."
    break
  fi
  sleep 1
done

port_ok=0
if (exec 3<>/dev/tcp/127.0.0.1/8484) 2>/dev/null; then
  exec 3>&- 3<&- || true
  port_ok=1
fi

# coop 0.1b (Slice A.9, audit fix B12): DB assertions for the custom Liquibase
# changesets (db/extensions/coop-*). Each individual changeset ID must be present
# so the smoke test fails when an already-applied changeset silently regresses.
db_ok=0
if [[ "$ok" == 1 && "$port_ok" == 1 ]]; then
  db_query() {
    compose exec -T "$DB_SERVICE" sh -c \
      'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot cosmic -N -B -e "$1"' sh "$1" 2>/dev/null
  }
  coop_changesets="$(db_query "SELECT COUNT(*) FROM DATABASECHANGELOG WHERE ID LIKE 'coop-%';")"
  charslot_default="$(db_query "SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'cosmic' AND TABLE_NAME = 'accounts' AND COLUMN_NAME = 'characterslots';")"
  useslots_default="$(db_query "SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'cosmic' AND TABLE_NAME = 'characters' AND COLUMN_NAME = 'useslots';")"
  storage_rows="$(db_query "SELECT COUNT(*) FROM storages;")"
  storage_dupes="$(db_query "SELECT COUNT(*) FROM (SELECT accountid, world, COUNT(*) c FROM storages GROUP BY accountid, world HAVING c > 1) d;")"
  stack_overrides="$(db_query "SELECT COUNT(*) FROM coop_stack_overrides;")"
  hint_rows="$(db_query "SELECT COUNT(*) FROM coop_milestone_hints;")"
  hint_seen_table="$(db_query "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'cosmic' AND TABLE_NAME = 'coop_character_hint_seen';")"
  hint_job_filter_col="$(db_query "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'cosmic' AND TABLE_NAME = 'coop_milestone_hints' AND COLUMN_NAME = 'job_filter';")"
  storage_uq="$(db_query "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'cosmic' AND TABLE_NAME = 'storages' AND INDEX_NAME = 'uq_storages_account_world';")"
  coop_ids_present="$(db_query "SELECT COUNT(*) FROM DATABASECHANGELOG WHERE ID IN ('coop-1001','coop-1002','coop-1003','coop-1010','coop-1011','coop-1012','coop-1020','coop-1030','coop-1031','coop-1032','coop-1033','coop-1100','coop-1210','coop-1211','coop-1212','coop-1220','coop-1221');")"
  wrong_stack_rows="$(db_query "SELECT COUNT(*) FROM coop_stack_overrides WHERE item_id IN (2100000,2100001,2100002,4001000,4001001,4001002,4001010,4001011,4001012);")"
  coop_1100_present="$(db_query "SELECT COUNT(*) FROM DATABASECHANGELOG WHERE ID = 'coop-1100';")"
  companion_table="$(db_query "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'cosmic' AND TABLE_NAME = 'coop_companion_bindings';")"
  companion_uq="$(db_query "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'cosmic' AND TABLE_NAME = 'coop_companion_bindings' AND INDEX_NAME = 'uq_coop_companion_companion';")"
  # coop 0.1b Early Game Remix: starter kits, their seed rows and the
  # early-game telemetry table must all be present.
  kits_table="$(db_query "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'cosmic' AND TABLE_NAME = 'coop_first_job_kits';")"
  kits_seed="$(db_query "SELECT COUNT(*) FROM coop_first_job_kits;")"
  cygnus_ammo="$(db_query "SELECT COUNT(*) FROM coop_first_job_kits WHERE (jobid = 1300 AND itemid = 2060000 AND quantity = 2000) OR (jobid = 1400 AND itemid = 2070000 AND quantity = 1000);")"
  telemetry_table="$(db_query "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'cosmic' AND TABLE_NAME = 'coop_early_game_exp_log';")"
  telemetry_source_default="$(db_query "SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'cosmic' AND TABLE_NAME = 'coop_early_game_exp_log' AND COLUMN_NAME = 'source';")"

  expected_ids=17
  if [[ "$coop_changesets" -ge "$expected_ids" \
        && "$coop_ids_present" -eq "$expected_ids" \
        && "$charslot_default" == "15" \
        && "$useslots_default" == "32" \
        && "$stack_overrides" -ge 45 \
        && "$wrong_stack_rows" -eq 0 \
        && "$hint_rows" -ge 6 \
        && "$hint_seen_table" -eq 1 \
        && "$hint_job_filter_col" -eq 1 \
        && "$storage_dupes" -eq 0 \
        && "$storage_uq" -ge 1 \
        && "$coop_1100_present" -eq 1 \
        && "$companion_table" -eq 1 \
        && "$companion_uq" -ge 1 \
        && "$kits_table" -eq 1 \
        && "$kits_seed" -ge 8 \
        && "$cygnus_ammo" -eq 2 \
        && "$telemetry_table" -eq 1 \
        && "$telemetry_source_default" == "UNATTRIBUTED" ]]; then
    log "DB checks passed: ${coop_changesets} coop changesets (${coop_ids_present}/${expected_ids} expected), charslots=${charslot_default}, useslots=${useslots_default}, stack overrides=${stack_overrides}, hints=${hint_rows}, hint table+col=${hint_seen_table}/${hint_job_filter_col}, wrong-family rows=${wrong_stack_rows}, storage dupes=${storage_dupes}, uq=${storage_uq}, companion table=${companion_table} uq=${companion_uq}, kits table=${kits_table} rows=${kits_seed} cygnus=${cygnus_ammo}, telemetry table=${telemetry_table} source=${telemetry_source_default}."
    db_ok=1
  else
    warn "DB checks failed:"
    warn "  coop changesets: ${coop_changesets:-<error>} (expected at least ${expected_ids}, ids_present ${coop_ids_present:-<error>})"
    warn "  characterslots default: ${charslot_default:-<error>} (expected 15)"
    warn "  useslots default: ${useslots_default:-<error>} (expected 32)"
    warn "  stack overrides: ${stack_overrides:-<error>} (expected >= 50)"
    warn "  wrong-family stack rows: ${wrong_stack_rows:-<error>} (expected 0)"
    warn "  hint rows: ${hint_rows:-<error>} (expected >= 6)"
    warn "  hint table exists: ${hint_seen_table:-<error>} (expected 1)"
    warn "  hint job_filter col exists: ${hint_job_filter_col:-<error>} (expected 1)"
    warn "  storage duplicate rows: ${storage_dupes:-<error>} (expected 0)"
    warn "  storage uq index: ${storage_uq:-<error>} (expected >= 1)"
    warn "  coop-1100 applied: ${coop_1100_present:-<error>} (expected 1)"
    warn "  companion bindings table: ${companion_table:-<error>} (expected 1)"
    warn "  companion unique index: ${companion_uq:-<error>} (expected >= 1)"
    warn "  first-job kits table: ${kits_table:-<error>} (expected 1)"
    warn "  first-job kits rows: ${kits_seed:-<error>} (expected >= 8)"
    warn "  Cygnus ammunition rows: ${cygnus_ammo:-<error>} (expected 2)"
    warn "  early-game telemetry table: ${telemetry_table:-<error>} (expected 1)"
    warn "  telemetry source default: ${telemetry_source_default:-<error>} (expected UNATTRIBUTED)"
  fi
fi

if [[ "$ok" == 1 && "$port_ok" == 1 && "$db_ok" == 1 ]]; then
  log "SMOKE TEST PASSED: server online, login port 8484 reachable, coop migrations applied."
  result=0
else
  [[ "$ok" == 1 ]] || warn "Online marker not found within ${TIMEOUT_S}s."
  [[ "$port_ok" == 1 ]] || warn "Login port 8484 not reachable."
  warn "Last server log lines:"
  compose logs --tail=80 "$SERVER_SERVICE" >&2 || true
  result=1
fi

log "Stopping stack after smoke test..."
compose down
exit "$result"
