#!/usr/bin/env bash
# DESTRUCTIVE: deletes ALL characters and every character-scoped row.
# Accounts, character slots and all migrations are KEPT.
#
# Why this script exists instead of a plain "DELETE FROM characters": only a
# handful of tables have ON DELETE CASCADE. The explicit cleanup plan below is
# audited against the schema because column names alone do not describe data
# ownership. In particular, inventoryitems mixes character and account data.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

ensure_env
load_env

[[ "${1:-}" == "--i-understand" ]] \
  || die "Refusing: this deletes ALL characters. Re-run with --i-understand."

SERVER_WAS_RUNNING=false
SERVER_STOPPED=false

if compose ps --status running --services 2>/dev/null | grep -qx "$SERVER_SERVICE"; then
  SERVER_WAS_RUNNING=true
  warn "The gameserver is running. It will be stopped so nobody is online."
fi

restore_server() {
  if [[ "$SERVER_WAS_RUNNING" == true && "$SERVER_STOPPED" == true ]]; then
    log "Starting the gameserver again..."
    compose up -d "$SERVER_SERVICE" >/dev/null
  fi
}
trap restore_server EXIT

confirm "Delete ALL characters and their data? (accounts + migrations are kept)"

log "Stopping the gameserver (DB stays up)..."
if [[ "$SERVER_WAS_RUNNING" == true ]]; then
  compose stop "$SERVER_SERVICE" >/dev/null
  SERVER_STOPPED=true
fi

db_mysql() {
  compose exec -T "$DB_SERVICE" sh -c \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot cosmic "$@"' sh "$@"
}

read -r -d '' WIPE_SQL <<'SQL' || true
SET SESSION sql_mode = CONCAT_WS(',', @@SESSION.sql_mode, 'STRICT_ALL_TABLES');

CREATE TEMPORARY TABLE wipe_preserved_state AS
SELECT
  (SELECT COUNT(*) FROM accounts) AS account_count,
  (SELECT COALESCE(SUM(characterslots), 0) FROM accounts) AS character_slot_count,
  (SELECT COUNT(*) FROM DATABASECHANGELOG) AS migration_count,
  (SELECT COUNT(*) FROM inventoryitems WHERE accountid IS NOT NULL) AS account_item_count,
  (SELECT COUNT(*) FROM inventoryequipment ie
     JOIN inventoryitems ii USING (inventoryitemid)
     WHERE ii.accountid IS NOT NULL) AS account_equipment_count,
  (SELECT COUNT(DISTINCT p.petid) FROM pets p
     JOIN inventoryitems ii ON ii.petid = p.petid
     WHERE ii.accountid IS NOT NULL) AS account_pet_count,
  (SELECT COUNT(*) FROM storages) AS storage_count;
CREATE TEMPORARY TABLE wipe_assertion (ok TINYINT NOT NULL);

START TRANSACTION;

-- Preserve account-owned storage/cash items and their equipment/pets.
DELETE pi FROM petignores pi
  JOIN pets p ON p.petid = pi.petid
  JOIN inventoryitems ii ON ii.petid = p.petid
  WHERE ii.characterid IS NOT NULL;
DELETE p FROM pets p
  JOIN inventoryitems ii ON ii.petid = p.petid
  WHERE ii.characterid IS NOT NULL;
DELETE r FROM rings r
  JOIN inventoryequipment ie ON ie.ringid = r.id
  JOIN inventoryitems ii ON ii.inventoryitemid = ie.inventoryitemid
  WHERE ii.characterid IS NOT NULL;
DELETE ie FROM inventoryequipment ie
  JOIN inventoryitems ii ON ii.inventoryitemid = ie.inventoryitemid
  WHERE ii.characterid IS NOT NULL;
DELETE FROM inventorymerchant;
DELETE FROM inventoryitems WHERE characterid IS NOT NULL;
DELETE ie FROM inventoryequipment ie
  LEFT JOIN inventoryitems ii ON ii.inventoryitemid = ie.inventoryitemid
  WHERE ii.inventoryitemid IS NULL;
DELETE p FROM pets p
  LEFT JOIN inventoryitems ii ON ii.petid = p.petid
  WHERE ii.inventoryitemid IS NULL;
DELETE r FROM rings r
  LEFT JOIN inventoryequipment ie ON ie.ringid = r.id
  LEFT JOIN inventoryitems ii ON ii.inventoryitemid = ie.inventoryitemid
                              AND ii.accountid IS NOT NULL
  WHERE ii.inventoryitemid IS NULL;

-- Indirect children and social/world records owned by the old characters.
DELETE FROM bbs_replies;
DELETE FROM bbs_threads;
DELETE FROM dueyitems;
DELETE FROM dueypackages;
DELETE FROM allianceguilds;
DELETE FROM guilds;
DELETE FROM alliance;
DELETE FROM playernpcs_equip;
DELETE FROM playernpcs;
DELETE FROM playernpcs_field;
DELETE FROM mts_cart;
DELETE FROM mts_items;
DELETE FROM marriages;
DELETE FROM newyear;
DELETE FROM gifts;
DELETE FROM notes;
DELETE FROM reports;

-- Direct character state. Foreign keys stay enabled throughout the wipe.
DELETE FROM area_info;
DELETE FROM bosslog_daily;
DELETE FROM bosslog_weekly;
DELETE FROM buddies;
DELETE FROM characterexplogs;
DELETE FROM cooldowns;
DELETE FROM coop_character_hint_seen;
DELETE FROM coop_companion_bindings;
DELETE FROM coop_early_game_exp_log;
DELETE FROM eventstats;
DELETE FROM famelog;
DELETE FROM family_entitlement;
DELETE FROM family_character;
DELETE FROM fredstorage;
DELETE FROM keymap;
DELETE FROM medalmaps;
DELETE FROM monsterbook;
DELETE FROM namechanges;
DELETE FROM playerdiseases;
DELETE FROM questprogress;
DELETE FROM queststatus;
DELETE FROM savedlocations;
DELETE FROM skillmacros;
DELETE FROM skills;
DELETE FROM trocklocations;
DELETE FROM wishlists;
DELETE FROM worldtransfers;
DELETE FROM characters;

-- A NULL assertion fails the batch before COMMIT, causing a full rollback.
INSERT INTO wipe_assertion (ok)
SELECT IF(
  account_count = (SELECT COUNT(*) FROM accounts)
  AND character_slot_count = (SELECT COALESCE(SUM(characterslots), 0) FROM accounts)
  AND migration_count = (SELECT COUNT(*) FROM DATABASECHANGELOG)
  AND account_item_count = (SELECT COUNT(*) FROM inventoryitems WHERE accountid IS NOT NULL)
  AND account_equipment_count = (SELECT COUNT(*) FROM inventoryequipment ie
                                   JOIN inventoryitems ii USING (inventoryitemid)
                                   WHERE ii.accountid IS NOT NULL)
  AND account_pet_count = (SELECT COUNT(DISTINCT p.petid) FROM pets p
                             JOIN inventoryitems ii ON ii.petid = p.petid
                             WHERE ii.accountid IS NOT NULL)
  AND storage_count = (SELECT COUNT(*) FROM storages),
  1,
  NULL
)
FROM wipe_preserved_state;

COMMIT;
SQL

log "Deleting character-scoped data in one transaction..."
db_mysql <<<"$WIPE_SQL"

if ! REMAINING="$(db_mysql -N -B -e "SELECT COUNT(*) FROM characters;")"; then
  die "Character deletion completed, but verification failed."
fi

[[ "$REMAINING" == "0" ]] || die "Character deletion verification failed: $REMAINING remain."

log "Remaining characters: $REMAINING"
log "Accounts, slot capacity, migrations, account inventory and storage are unchanged."

log "Done."
