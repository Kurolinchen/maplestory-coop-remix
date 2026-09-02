#!/usr/bin/env bash
# Remote dev status.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

VPS_HOST="${VPS_HOST:-}"
[[ -n "$VPS_HOST" ]] || die "VPS_HOST not set - remote operations are not configured yet."

ssh "$VPS_HOST" bash -s <<'REMOTE'
set -euo pipefail
cd /opt/maple-dev/app
echo "== git =="; git log --oneline -3 2>/dev/null || true
echo "== compose =="; docker compose -f ../config/docker-compose.yml --env-file ../config/.env ps 2>/dev/null || true
echo "== db volume =="; docker volume ls --filter name=maple-dev || true
echo "== migrations =="
docker compose -f ../config/docker-compose.yml --env-file ../config/.env exec -T db sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot cosmic -N -B \
   -e "SELECT COUNT(*) FROM DATABASECHANGELOG WHERE ID LIKE \"coop-%\"; \
       SELECT COUNT(*) FROM DATABASECHANGELOG WHERE ID IN (\"coop-1212\",\"coop-1221\"); \
       SELECT COUNT(*) FROM coop_first_job_kits; \
       SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS \
         WHERE TABLE_SCHEMA = \"cosmic\" AND TABLE_NAME = \"coop_early_game_exp_log\" \
           AND COLUMN_NAME = \"source\"; \
       SELECT COUNT(*) FROM characters;"' 2>/dev/null \
  | paste -d' ' - - - - - \
  | awk '{ printf "coop changesets=%s | 0.1b fixes applied=%s | kit rows=%s | telemetry source=%s | characters=%s\n", $1, $2, $3, $4, $5 }'
REMOTE
