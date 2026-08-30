#!/usr/bin/env bash
# Restore a dev DB dump. Usage: ops/restore-dev-db.sh [dump-file]   (default: newest backup)
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

ensure_env
load_env

dump="${1:-}"
if [[ -z "$dump" ]]; then
  dump="$(ls -1t "$BACKUP_DIR"/cosmic-dev-*.sql.gz 2>/dev/null | head -n1 || true)"
  [[ -n "$dump" ]] || die "No backups found in $BACKUP_DIR (run ops/backup-dev-db.sh first)."
fi
[[ -f "$dump" ]] || die "Dump file not found: $dump"

warn "This OVERWRITES the current dev database with: $dump"
confirm "Restore now?"

log "Stopping server during restore..."
compose stop "$SERVER_SERVICE" || true

log "Restoring..."
if [[ "$dump" == *.gz ]]; then
  gunzip -c "$dump" | compose exec -T "$DB_SERVICE" mysql -uroot -p"$DB_ROOT_PASSWORD"
else
  compose exec -T "$DB_SERVICE" mysql -uroot -p"$DB_ROOT_PASSWORD" < "$dump"
fi

log "Restore done. Restarting server..."
compose start "$SERVER_SERVICE"
log "Finished."
