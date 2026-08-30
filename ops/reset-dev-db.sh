#!/usr/bin/env bash
# DESTRUCTIVE: wipe the dev DB volume entirely. Liquibase rebuilds schema + seed data on next boot.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

ensure_env

warn "This DESTROYS the entire dev database (accounts, characters, items, everything)."
warn "Make a backup first if unsure: ops/backup-dev-db.sh"
confirm "Really reset the dev database?"
read -r -p "Type RESET to confirm: " reply || reply=""
[[ "$reply" == "RESET" ]] || die "Aborted."

log "Taking stack down and removing DB volume..."
compose down --volumes

log "Done. Next ops/start.sh will create a fresh database (Liquibase migrations run on boot)."
