# Companion Party Bot MVP (Milestone 0.1b)

Status: implemented (pending playtest)
Branch: `feature/0.1b-earlygame-companions`

## Purpose

Give a solo or small-group player an OPTIONAL companion: a normal alternate
character, controlled server-side, that follows the owner, fights alongside them
and shares the normal party/EXP/loot rules.

**Companions are optional. Solo remains fully supported.** No content,
progression step or reward requires a companion, and companions never receive
hidden bonuses.

## Design decisions

1. **A companion IS a real character.** It is loaded through the standard
   `Character.loadCharFromDB` path, saves through `Character.saveCharToDB`, and
   participates in kill/EXP/drop accounting through `MapleMap.damageMonster`
   and `Character.pickupItem`. The only new server-side state is the binding
   row; everything else is existing character state.
2. **Not a connected session.** A companion is hosted by a headless `Client`
   subclass whose `sendPacket` is a no-op. It is deliberately NOT registered in
   `Channel`/`World` `PlayerStorage`, so channel capacity, login reuse, buddy
   presence and the disconnect path stay untouched.
3. **Map-local and party-visible only.** The companion is attached to the
   current `MapleMap` and to the owner's real `Party`. Nothing else.
4. **Failing safe is the default.** Any unexpected transition, missing
   ammunition, unsupported map or death results in "do nothing" or "dismiss and
   save", never in a partially attached bot.

## External references (pinned, NOT merged)

Per the owner's explicit rule, no external fork was merged, no bulk copy was
made, and `P0nk/Cosmic` remains our upstream lineage.

| Repository | Branch | Pinned commit | Role in this work |
|---|---|---|---|
| `P0nk/Cosmic` | `master` | `fec53bc77` (tag v1.1.3) | Upstream lineage, architecture baseline |
| `nutnnut/Cosmic` | `master` | `b684bf78588d50512bcd62db9bb7cb85e4f73829` | Read-only reference for what a full autonomy system looks like |
| `NDBellisario/cosmic` | `master` | `b01cf27833f568cde52a0a70a38532474eedd4d9` | Read-only reference, smaller historical implementation |

**Why no code was ported from either reference.** Listing
`nutnnut/Cosmic:src/main/java/server/bots` shows a complete artificial-population
system — `BotGenerator`, `BotGachaponManager`, `BotFarmingCostModel`,
`BotConsoleTap`, `BotAirshowManager`, `BotFamiliarityManager` and ~45 further
classes. That is precisely the "advanced autonomy" this milestone defers.
Porting it would have dragged in ownerless bot population, economy automation
and RTS-style behaviour, none of which are wanted for a companion party member.
`NDBellisario/cosmic` was inspected as the smaller alternative but its shape
also targets population simulation rather than a single owned companion.

Our implementation is therefore written from scratch against our own Cosmic
architecture, using only the existing public APIs documented below.

## Architecture

| Class | Responsibility |
|---|---|
| `coop.companion.CompanionController` | All policy: enablement gate, ownership verification, per-owner/per-account limits, map allowlist, job tier. Shared by commands, disconnect hook and shutdown hook. |
| `coop.companion.CompanionManager` | In-process registry (by owner, by companion), double-register rejection, shutdown flag. |
| `coop.companion.CompanionSession` | Identity + state machine (`NEW → ACTIVE → DISMISSING → CLOSED`, plus `SAVE_FAILED`) with per-session lock and tick counters. |
| `coop.companion.CompanionBindingRepository` | `coop_companion_bindings` persistence and authoritative ownership verification (fresh DB read against `characters`, never trusting a cached row). |
| `coop.companion.CompanionClient` | Headless `Client` subclass; `sendPacket` is a no-op; `detach()` releases the character (never calls the final `Client.disconnect`). |
| `coop.companion.CompanionLifecycleService` | spawn / dismiss / `transferToMap`; rollback of partial attach operations. |
| `coop.companion.CompanionMapPolicy` | Map/portal eligibility rules, including runtime entry-script availability. |
| `coop.companion.CompanionFollowController` | Same-map follow + bounded static-portal following. |
| `coop.companion.CompanionMovementService` | Safe foothold resolution and complete server-authored movement packets. |
| `coop.companion.CompanionTickScheduler` | One tick per active session at `coop.companion.tick_ms`. |
| `coop.companion.CompanionCombatProfile` | Job-family classification (audited from OUR `client/Job.java`). |
| `coop.companion.CompanionCombatController` | Target selection, bounded damage, incoming contact damage, ammunition/MP consumption. |
| `coop.companion.CompanionConsumableService` | HP/MP potion use from the companion's own inventory. |
| `coop.companion.CompanionLootController` | Opt-in, one-item-per-pass looting via `Character.pickupItem`. |
| `coop.companion.CompanionEquipmentService` | Owner-directed equip/unequip via `InventoryManipulator`. |

### Cosmic touchpoints (kept minimal)

| File | Change |
|---|---|
| `client/command/CommandsExecutor.java` | registers `@companion` (gm0) |
| `client/Client.java` | owner-disconnect hook at the top of `disconnectInternal` |
| `client/Character.java` | checked synchronous save result for lifecycle-safe dismissal |
| `net/server/Server.java` | `shutdownAll()` at the top of `shutdownInternal`, before worlds dispose their maps |
| `scripting/map/MapScriptManager.java` | cache-aware entry-script availability query |
| `config/YamlConfig.java` | unchanged; the `coop.companion` block rides on the existing `CoopConfig` field |
| `coop/config/CoopConfig.java` | nested `CompanionConfig` block |
| `coop/config/CoopDefaults.java` | null-safe, clamped accessors |

## Migration

`src/main/resources/db/extensions/coop-1100-companion-bindings.xml`:

```sql
coop_companion_bindings (
  owner_character_id      INT PK,
  companion_character_id  INT UNIQUE,
  account_id              INT,
  world                   INT,
  mode                    VARCHAR(16) DEFAULT 'PASSIVE',
  loot_enabled            BOOLEAN DEFAULT FALSE,
  created_at / updated_at TIMESTAMP
)
```

Both character columns carry `FOREIGN KEY … ON DELETE CASCADE`. `account_id`
and `world` are denormalised for audit speed only — runtime ownership always
re-verifies against `characters.accountid` / `characters.world`.

## Commands

```
@companion bind <character-name>     # register an owned alt (same account + world)
@companion unbind                    # clear the binding (dismisses an active companion)
@companion spawn                     # bring the companion into the current map + party
@companion dismiss                   # save and remove the companion
@companion mode <passive|follow|grind|stay>
@companion loot <on|off>
@companion equip <slot> <target-slot>
@companion status
```

## Configuration (`config.yaml`, `coop.companion`)

Every key is clamped in `CoopDefaults`. The dangerous defaults stay safe:

| Key | Default | Note |
|---|---|---|
| `enabled` | `false` | Must be turned on explicitly for a playtest |
| `allowed_map_ids` | `[]` | Empty means NO map is eligible, never "everywhere" |
| `max_active_per_owner` / `max_active_per_account` | `1` / `1` | Clamped 1..6 (party cap) |
| `allowed_job_tier` | `1` | First job only |
| `tick_ms` | `500` | Clamped 100..10000 |
| `portal_fallback_enabled` | `false` | Off until the cross-map audit signs off |
| `loot_enabled_default` | `false` | Looting is opt-in |
| `death_dismiss` | `true` | A dead companion is dismissed, not auto-respawned |
| `allow_bosses` | `false` | Companions do not fight bosses |
| `outgoing_damage_*` | `0.70 / 1.00 / 0.25 / 100000` | min ratio, max ratio, HP-fraction cap, absolute cap |
| `incoming_damage_*` | `true / 1800 / 90 / 1 / 0.35` | enabled, interval, contact range, min, max ratio |
| `hp_potion_ratio` / `mp_potion_ratio` | `0.45` / `0.25` | drink when the pool drops below this fraction |
| `consume_interval_ms` | `1500` | minimum gap between two potion uses |
| `allowed_hp_potions` / `allowed_mp_potions` | `[]` | empty = any recovery item that heals the needed pool |

## Integrity rules (all enforced, all tested)

- **No item duplication.** Every pickup goes through `Character.pickupItem`;
  ammunition is removed through `InventoryManipulator`. Nothing is created.
- **No meso/EXP duplication.** All damage goes through `MapleMap.damageMonster`,
  the same authoritative path a real player's attack uses. Party EXP is
  distributed by the existing `Monster.distributeExperience` pipeline — there is
  no companion-specific multiplier.
- **No warp exploit.** Companions only traverse scriptless, open, allowlisted
  portals whose target matches the owner's observed destination, and only within
  the grace window. The catch-up fallback is inert unless explicitly enabled and
  is additionally gated by the same transition checks.
- **No content bypass.** Instanced ranges (PQ/event ≥ 910000000, dojo
  925–926M, event fields 950–969M) are hard-blocked, so a companion can never
  satisfy a party-size check or duplicate an instanced reward.
- **No hidden advantage.** Damage is derived from the companion's real stats and
  equipment via `calculateMaxBaseDamage` / `calculateMaxBaseMagicDamage`, then
  clamped by both a fraction of the monster's max HP and an absolute ceiling.
- **Ownership.** Binding and spawn both re-verify against `characters`; an
  operator cannot bind or spawn another account's character.
- **Persistence.** Dismissal uses the SYNCHRONOUS `saveCharToDB(true)` overload.
  A failed save moves the session to `SAVE_FAILED` and holds it rather than
  dropping the character. The owner-disconnect hook and the server-shutdown hook
  both run before the owner's map/party teardown.

## Persistence caveats (important for a long playtest)

A companion is loaded **outside** `PlayerStorage`, which means:

- **`CharacterAutosaverTask` never sees it.** The hourly autosave and
  `!saveall` both iterate world player storage, so a companion's progress is
  only written on a **successful dismiss**, on owner disconnect/logout, or on a
  clean server shutdown. A `SIGKILL` or a power loss loses everything since the
  companion was spawned.
- **A failed save holds the session** in `SAVE_FAILED` instead of releasing it.
  That is deliberate (releasing would allow a second load of the same row), but
  it currently means the owner cannot re-spawn until the server restarts. A
  `@companion force-release` escape hatch is a follow-up.
- **Do not change channel while a companion is out.** Channel change does not
  go through `disconnectInternal`; the hook was added to `Client.changeChannel`
  so the companion is dismissed there, but if you hit an edge case the safe
  move is `@companion dismiss` before switching channels.

## Known limitations (deliberate for this milestone)

- **No autonomous routing.** The companion follows the owner; it does not travel
  on its own. Taxis, ferries and World Tour are out of scope — the companion is
  dismissed if the owner leaves an allowlisted map.
- **No ropes or ladders.** Same-map follow clamps each step and validates the
  foothold; it does not path-find.
- **No mob skills, diseases or reflect** in incoming damage modelling — only
  nearest-monster contact damage.
- **No automatic equipment optimiser.** Equipping is owner-directed until
  two-handed/offhand, ring, cash-slot and stat-trade-off logic is tested.
- **No support/healing skills.** `@companion mode support` is recognised but
  explicitly not implemented.
- **No PQ participation.** Companions are dismissed before entering any
  instanced content.
- **Movement presentation.** The companion's movement is broadcast as a single
  absolute-movement fragment, so it may visually "step" rather than glide. If
  that looks wrong in the v83 client, report it and it will be refined.

## Manual gameplay-test checklist

Prerequisites: set `coop.companion.enabled: true` and add your training map ids
to `coop.companion.allowed_map_ids`, then `ops/build.sh` and
`ops/start.sh --build`.

### Phase A — binding and spawning

1. Create two characters on one account; leave the second one offline.
2. Log in as the first, form a party alone (you must be leader).
3. `@companion bind <second-character-name>` → expect success.
4. `@companion status` → binding shown, inactive.
5. Walk onto an allowlisted map, `@companion spawn` → companion appears beside
   you, immediately settles onto the same foothold and joins the party UI.
6. `@companion status` → live session, mode, state, map.
7. Before either character moves or takes damage, verify that the companion's
   party HP bar is populated rather than empty.
8. Try binding a character from another account (or an online character) → both
   must fail with a clear reason.

### Phase B — following

9. Walk across the map; the companion should stay within follow distance.
10. Walk through a normal static portal into another allowlisted map → the
   companion should follow.
11. Walk into a town or an unallowlisted map → the companion must dismiss and
    save (check the log line, not just the UI).
12. `@companion status` after dismissal → inactive.

### Phase C — combat

13. `@companion mode grind`, dismiss, spawn again.
14. Enter a training map with level-appropriate monsters; the companion should
    target the nearest one and attack.
15. Watch the companion's HP; it should drink from its own inventory when it
    drops below the configured ratio.
16. Empty the companion's ammunition (for Bowman/Thief/Gunslinger) or MP (for
    Magician) → it must stop attacking rather than attack for free.
17. Compare the damage the companion deals against the same monster played
    manually — it must be in the same ballpark, never higher.

### Phase D — loot and equipment

18. `@companion loot on` (requires dismissal first), spawn, kill monsters → the
    companion picks up nearby legal loot into ITS OWN inventory.
19. Verify the owner's inventory does NOT receive the companion's loot.
20. `@companion equip <slot> <target>` → the item is equipped through normal
    validation; an invalid target must be rejected.

### Phase E — death and persistence

21. Let the companion die (or force it) → it must be dismissed rather than
    loop-respawning.
22. `@companion dismiss`, then check the companion's character row: HP/MP, map,
    inventory and equipment must reflect the session.
23. Log in as the companion character manually → everything must be intact and
    the character must be playable.
24. Restart the server with a companion active → the shutdown hook must save it;
    check the log for "Companion shutdown save FAILED" (there must be none).

### Phase F — integrity spot checks

25. Watch for any duplicate item, meso or EXP during the session.
26. Confirm the companion never appears in channel player counts or `@online`.
27. Confirm the companion never enters a PQ lobby or boss map.

## Deferred (explicitly out of scope for this milestone)

Ownerless bot population, `!botpop`, RTS/web bot map, LLM/Ollama chat,
independent economy, automated Gachapon, Maker automation, unlimited
autopilot, autonomous account generation, and the full autonomous travel stack
(taxis, ferries, World Tour, multi-hop map graph routing).
