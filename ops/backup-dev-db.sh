#!/usr/bin/env bash
# Dump the dev database to ops/backups/. Usage: ops/backup-dev-db.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

ensure_env
load_env
mkdir -p "$BACKUP_DIR"

ts="$(date +%Y%m%d-%H%M%S)"
out="$BACKUP_DIR/cosmic-dev-$ts.sql.gz"

log "Dumping database 'cosmic' -> $out"
compose exec -T "$DB_SERVICE" \
  mysqldump -uroot -p"$DB_ROOT_PASSWORD" --single-transaction --databases cosmic \
  | gzip > "$out"

# keep only the newest 10 backups
ls -1t "$BACKUP_DIR"/cosmic-dev-*.sql.gz 2>/dev/null | tail -n +11 | xargs -r rm -f

log "Backup done: $(du -h "$out" | cut -f1) $out"
