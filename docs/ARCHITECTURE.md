# Architecture — Cosmic v83 baseline (verified)

All references verified against commit `fec53bc77` (upstream master, 2026 baseline).
Repo root: this repository. Java sources under `src/main/java`, JS under `scripts/`,
Liquibase under `src/main/resources/db`.

> Rule of thumb for agents: when this document and reality disagree, reality wins — verify
> and update this document.

## 1. Server lifecycle

| Concern | Location |
|---|---|
| `main()` | `net/server/Server.java:1003` (sets GraalVM `WarnInterpreterOnly=false`, calls `init()`) |
| `init()` boot sequence | `net/server/Server.java:861-950` — DB pool → migrations → warmup → timers → worlds → login server → event reload |
| DB pool init | `Server.java:869-871` → `tools/DatabaseConnection.initializeConnectionPool()` (`tools/DatabaseConnection.java:79`) |
| Liquibase run | `Server.java:873` → `database/DatabaseMigrations.java:23-43` (root changelog constant line 21) |
| Async warmup | `Server.java:877-884` (SkillFactory, CashItemFactory, Quest, SkillbookInformationProvider) |
| World creation | `Server.initWorld()` `Server.java:414-481`; rates read from config at 430-440 |
| Login server bind | `Server.java:936` → `net/netty/LoginServer.java:9-38`, port **8484** |
| Channel ports | `net/server/channel/Channel.java:78` (`BASE_PORT=7575`), formula `7575+(ch-1)+world*100` at 131 |
| Shutdown | `Server.java:1916-1979` (`shutdown(boolean restart)`); hook registered at 865-867 if `SHUTDOWNHOOK` |
| Timers/threads | `server/TimerManager.java:39-156`, `server/ThreadManager.java:34-74`, periodic tasks `Server.java:980-1001` |
| Boot success log | `Server.java:942` — `"Cosmic is now online after {} ms."` |

## 2. Configuration

- `config.yaml` (repo root) → parsed by `config/YamlConfig.java:13-31` (yamlbeans). Charset
  pre-parse: `constants/string/CharsetConstants.java:32,63-85`.
- Server-wide flags: `config/ServerConfig.java` — DB block lines 9-13 (`DB_URL_FORMAT`,
  `DB_HOST`, `DB_USER`, `DB_PASS`, `INIT_CONNECTION_POOL_TIMEOUT`), login flags 16-37
  (`WORLDS`, `ENABLE_PIC/PIN`, `AUTOMATIC_REGISTER`, `COLLECTIVE_CHARSLOT`…), hosts 47-50.
- Per-world config: `config/WorldConfig.java:4-15` — `exp_rate`, `meso_rate`, `drop_rate`,
  `boss_drop_rate`, `quest_rate`, `travel_rate`, `fishing_rate`, `channels`.
- Rate flow: `config.yaml` → `Server.initWorld` (430-445) → `net/server/world/World.java`
  getters (359-455) → `client/Character.setWorldRates()` (`Character.java:6531-6536`, applied
  on login in `net/server/channel/handlers/PlayerLoggedinHandler.java:419`). Player-side
  compound rate getters: `Character.java:4933-4994`. Coupon rates: `Server.java:614-657`.

## 3. Database

- Connections: `tools/DatabaseConnection.java` — HikariCP (max pool 10, 30 s timeout) + JDBI;
  `getConnection()` :30, `getHandle()` :38, `DB_HOST` env override at :46-53 (Docker support).
- Migrations: `src/main/resources/db/changelog-root.xml:8-13` includes
  `changelog-tables.xml` (ids 1-24 → `db/tables/NNN-*.sql`), `changelog-data.xml`
  (ids 101-161 → `db/data/1NN-*-data.sql`), and **`db/extensions/` via `includeAll`
  (`errorIfMissingOrEmpty=false`)** → custom changesets belong in `db/extensions/`.
- No transaction manager: manual JDBC transactions (e.g. `Character.saveCharToDB`,
  `Server.java:1617-1690`). DAO example: `database/note/NoteDao.java`.
- Key tables: `accounts` (`db/tables/001-account.sql`, incl. `nxCredit`, `maplePoint`,
  `nxPrepaid`, `characterslots DEFAULT 3` — bumped to 15 by `db/extensions/coop-1001`),
  `characters`, `inventoryitems`/`inventoryequipment`
  (003), quest tables (006), `drop_data`/`drop_data_global` (009), bosslogs (023).
- Custom (coop) schema/data lives in `db/extensions/coop-*` (auto-included, see above):
  slot-default bumps `coop-1001..1003`, stack-override table+seed `coop-1010..1011`
  (`coop_stack_overrides` consulted by `ItemInformationProvider.getSlotMax`).

## 4. WZ data loading

- `provider/wz/WZFiles.java:6-19,37-45` — WZ dir resolution (system property `wz-path`, else `wz/`)
- `provider/DataProviderFactory.java:29-37`, `provider/wz/XMLWZFile.java:37-89` (lazy DOM parse),
  `provider/DataTool.java:28-165`
- Consumers: `server/ItemInformationProvider.java:144`, `client/SkillFactory.java:90-115`,
  `server/life/LifeFactory.java:45-48`, `server/maps/MapFactory.java:50-57`,
  `server/quest/Quest.java:115`, `server/life/MobSkillFactory.java:47`.

## 5. Accounts, login, characters

- Login: `client/Client.login` (`client/Client.java:641-727`); packet entry
  `net/server/handlers/login/LoginPasswordHandler.java:63-138`; **AUTOMATIC_REGISTER** insert
  at :80-99; bcrypt migration :101-112.
- Character creation: dispatch `net/server/handlers/login/CreateCharHandler.java:51-64` →
  `client/creator/{novice,veteran}` creators → `client/creator/CharacterFactory.java:41-106` →
  DB insert `Character.insertNewChar` (`client/Character.java:8064-8225`).
- Load: `Character.loadCharFromDB` (`Character.java:6846+`); channel join
  `PlayerLoggedinHandler.java:161-174`.
- Save: `Character.saveCharToDB` (`Character.java:8244-8645`) — ONE manual transaction
  covering stats, skills, quests, cash shop, storage; autosave routes through `USE_AUTOSAVE`.
- Character slots: DB `accounts.characterslots` (upstream default 3, raised to 15 by
  `db/extensions/coop-1001`); `Client.java:1361-1399` (+`setCharacterSlotsPersistent`, coop 0.1);
  buying slots via cash shop action 0x08 capped at `coop.max_character_slots`
  (`net/server/channel/handlers/CashOperationHandler.java:244-266`). AUTOMATIC_REGISTER writes
  `coop.default_character_slots` (`net/server/handlers/login/LoginPasswordHandler.java:80-99`).
  `COLLECTIVE_CHARSLOT` toggles account-wide vs per-world counting
  (`config/ServerConfig.java:36`, `CharacterFactory.java:42`, `tools/PacketCreator.java:914`).

## 6. Inventories, storage, equipment

- Items: `client/inventory/Item.java`, `Equip.java` (stat fields :73), `InventoryType.java:27-35`,
  `Inventory.java` (move/stack logic 244-273), `ItemFactory.java:39-113` (persistence:
  INVENTORY/STORAGE/CASH_*).
- Server-side ops + packets: `client/inventory/manipulator/InventoryManipulator.java`
  (addFromDrop 173, move 486, equip 523, unequip 646, drop 705). Client packet entry
  `net/server/channel/handlers/ItemMoveHandler.java:36`.
- Storage: `server/Storage.java` (create :72 — initial slots from `coop.default_storage_slots`,
  load :83, cap `coop.storage_slot_cap` :112, save :132),
  `server/StorageInventory.java:33-92`, world registry `net/server/world/World.java:505-532`,
  player ops `client/processor/npc/StorageProcessor.java:49-245`.
- Equip stats applied: `Character.recalcEquipStats` (7600), `reapplyLocalStats` (7640),
  recalc chain 7765-7831, trigger `equipChanged` (2845).

## 7. Jobs, skills, stats

- Jobs: `client/Job.java:24-135` (enum + `isA`); job change `Character.changeJob` (:1141-1240,
  SP/AP grants, HP/MP rolls, +4 inventory slots).
- Skills: `client/Skill.java:30-99`, `client/SkillFactory.java:88-122`; learning
  `Character.changeSkillLevel` (:1815-1833); SP assign
  `client/processor/stat/AssignSPProcessor.java:61-98`; casting entry
  `net/server/channel/handlers/SpecialMoveHandler.java:50-161`; attack skill damage via
  attack handlers (e.g. `RangedAttackHandler`).
- Stats/AP: `client/Stat.java:24-44`; `client/processor/stat/AssignAPProcessor.java` —
  AP assign :618-667, **AP reset :486**, HP change :669, MP change :764; auto-assign :57.
- Level-up: `Character.levelUp` (:6281-6405, `level++` at 6382, HP/MP rolls 6323-6357, SP via
  `levelUpGainSp` :6266); caps: Cygnus 120 / others 200 (`Character.getMaxClassLevel` :5303),
  job-stage caps `constants/game/GameConstants.java:464-483`.

## 8. EXP & leveling

- Kill EXP distribution: `server/life/Monster.java` — `killBy`/`distributeExperience` 602-690;
  **party formula** `distributePartyExperience` 548-600 and `distributePlayerExperience`
  535-546 (common mod by level share + MVP mod + 5%×members party bonus; config mods
  `ServerConfig.java:141-145`); rate multipliers applied at 732/746 in `giveExpToCharacter`
  728-758 (Holy Symbol, EXP buffs, world/coupon/player rates).
- Player side: `Character.gainExp` (:3090-3189; curse halving, pendant bonus, level-up loop
  3160-3167, EXP logging 3174-3184).
- Quest EXP: `server/quest/actions/ExpAction.java:53-58` (quest rate optional via `USE_QUEST_RATE`).
- EXP audit log: `server/ExpLogger.java:20-95` → table `characterexplogs` (batch insert every 60 s;
  gated by `USE_EXP_GAIN_LOG`). Useful data source for balance analysis.

## 9. NX / Cash Shop

- `server/CashShop.java` — currency types NX_CREDIT=1 / MAPLE_POINT=2 / NX_PREPAID=4 (:63-66);
  loaded from `accounts` (:101-111); `gainCash` :330; persisted in `save(con)` :483-515
  (called from char save `Character.java:8625-8627`).
- Entry: `net/server/channel/handlers/EnterCashShopHandler.java:37-94`; operations
  `net/server/channel/handlers/CashOperationHandler.java` (buy 0x03 :76-113, char slots 0x08
  :244-266, inventory/storage slots 0x06/0x07). Coupons: `CouponCodeHandler.java:216`;
  MTS refunds: `MTSHandler.java:420,473`.

## 10. Quests

- Data/logic: `server/quest/Quest.java` (WZ load :115-220, `canStart` 287, `canComplete` 301,
  `start` 316, `complete` 335, `forceStart` 371, `forceComplete` 407); requirements
  `server/quest/requirements/` (21 classes), rewards `server/quest/actions/` (13 classes).
- State: `client/QuestStatus.java:36-265`. Packet entry
  `net/server/channel/handlers/QuestActionHandler.java:70-131`.
- Scripts: `scripting/quest/QuestScriptManager.java:51-145`, `QuestActionManager.java:34-89`,
  files `scripts/quest/*.js` (253, template `scripts/QUEST Base.js`).

## 11. PQs / events

- Java: `server/partyquest/` — `PartyQuest.java:38` (base + static PQ exp tables :77-115),
  `Pyramid`, `AriantColiseum`, `MonsterCarnival(+Party)`, `CarnivalFactory`, `GuardianSpawnPoint`;
  GM events `server/events/` (`Events.java`, `RescueGaga`, `gm/{Snowball,Coconut,Fitness,Ola,OxQuiz}`).
- Script engine: `scripting/event/EventScriptManager.java:58-91` (compiles `scripts/event/*.js`,
  loaded per channel in `Channel.java:142-148`), `scripting/event/EventManager.java`
  (lobbies :83, `startInstance` overloads 367-653, `getEligibleParty` 722-739),
  `scripting/event/EventInstanceManager.java:67` (registerParty 363, startEvent 1085,
  setEventCleared 1095).
- Scripts declare `isPq`, `minPlayers/maxPlayers`, level range, maps, `eventTime`
  (e.g. `scripts/event/KerningPQ.js:26-45`, `BalrogBattle.js:27-28,113`). 108 event scripts.

## 12. Bosses & expeditions

- No boss Java class — boss flag in `server/life/MonsterStats.java:40-55` (`boss` :43,
  `isBoss` :117); HP bar `server/life/Monster.java:1035-1041`. Boss data: `LifeFactory.java:50-61,130`.
- Mechanics: Horntail spawn `server/maps/MapleMap.java:3997-4041` (defeat check 3987); Zakum
  arm→body reveal `MapleMap.java:1394-1419`; victory broadcasts 1309-1314.
- Boss event scripts: `scripts/event/{Zakum,Horntail,Papulatus,PinkBean,Balrog,Scarga}Battle.js` etc.
- Expeditions: `server/expeditions/Expedition.java:60-256` (registration 129-153, join rules
  207-229); `ExpeditionType.java:31-44` — **ZAKUM(6,30,50,255,5)**, HORNTAIL(6,30,…),
  PINKBEAN(6,30,120,…), ARIANT(2,7,…); solo override via `USE_ENABLE_SOLO_EXPEDITIONS` (:60-62).
  Daily quotas: `ExpeditionBossLog.java:42-49,178` (`bosslog_daily`/`bosslog_weekly`).
- Created from scripts: `scripting/AbstractPlayerInteraction.java:1078-1096`
  (e.g. `scripts/npc/2030013.js` Adobis, `scripts/npc/2083004.js` Horntail gatekeeper).

## 13. Drops & loot

- Entries: `server/life/MonsterDropEntry.java:27`, `MonsterGlobalDropEntry.java:26`; loaded from
  DB `drop_data`/`drop_data_global` by `server/life/MonsterInformationProvider.java:79-173`.
- Filtering vs killer party: `server/loot/LootManager.java:33-97` (+ `LootInventory`).
- Generation on kill: `server/maps/MapleMap.java` — `dropFromMonster` 734-778 (rate pick :741
  `getDropRate()` vs `getBossDropRate()`), chance roll `dropChance = de.chance * chRate * cardRate`
  :654-703; meso drops :675-689. Seed data: `db/data/151-global-drop-data.sql`, `152-drop-data.sql`.

## 14. NPC scripting (GraalVM JS)

- Engine: `scripting/AbstractScriptManager.java:39-92` (engine `graal.js`, `Java.type` host
  access enabled :83-87).
- NPC: `scripting/npc/NPCScriptManager.java:43-209` (loads `scripts/npc/{name}.js`, binds `cm`),
  `scripting/npc/NPCConversationManager.java:79` (~1100 lines of dialog/shop/PQ/expedition API),
  entry `net/server/channel/handlers/NPCTalkHandler.java:42-97`.
- Portal: `scripting/portal/PortalScriptManager.java:36-82` (bound to `PortalScript` interface);
  item scripts: `scripting/item/ItemScriptManager.java:37`.
- Counts: npc 708, portal 458, event 108, quest 253. Templates: `scripts/NPC Base.js`,
  `scripts/QUEST Base.js`, `scripts/REACT Base.js`.
- All scripts are validated by `scripting.ScriptEvaluationTest` (evaluates every script).

## 15. Commands

- `client/command/CommandsExecutor.java:211-241` — prefixes `@` (players) and `!` (GM);
  registration by GM level (:233-241, `registerLv0Commands`…); handling :247-292 (rank check
  :279-282). Implementations in `client/command/commands/gm0`…`gm6` (e.g. gm0 `HelpCommand`,
  `RatesCommand`; gm4 boss-spawn `ZakumCommand`/`HorntailCommand`, registered :512-513).
- Chat entry: `net/server/channel/handlers/GeneralChatHandler.java:53-54`.

## 16. Maps, channels, mobs

- Channel server: `net/server/channel/Channel.java:76-165` (players, MapManager, EventScriptManager,
  expeditions registry 405-431, events 452-464).
- Maps: `server/maps/MapManager.java:30-141` (cache + `getDisposableMap` for PQ instances),
  `server/maps/MapFactory.java:133-336` (WZ + DB life), `server/maps/MapleMap.java:105` (4400+ lines).
- Mobs: `server/life/Monster.java:88` (skills/attacks :1501-1601), `MonsterStats.java`,
  `MobSkillFactory.java:45-139`, spawn loop `server/life/SpawnPoint.java:32-134` +
  `MapleMap.addMonsterSpawn` :3013 / respawn loop :3445,3564.

## 17. Logging

- `src/main/resources/log4j2.xml` — console + rolling file root; dedicated file loggers:
  chat (`logs/chat.log`), trades, expeditions, gachapon, MapleLeaf, packet log.
- `server/ChatLogger.java:8-19` (gated by `USE_ENABLE_CHAT_LOG`), `server/ExpLogger.java`
  (batched DB log, gated by `USE_EXP_GAIN_LOG`).

## 18. Tests

- JUnit 5 + Mockito; suite lives in `src/test/java` (pattern examples:
  `net/server/channel/handlers/CashShopSurpriseHandlerTest.java`, `scripting/ScriptEvaluationTest.java`).
- Helpers: `src/test/java/testutil/` — `HandlerTest.java` (mocked Client/Character),
  `Packets.buildInPacket`, `Items`, `Mocks`. See `docs/TESTING.md`.

## Integration points for custom systems

Where our systems will hook in (planned; keep the footprint minimal):

- **Rebirth**: NPC/script entry (`scripts/npc/`), new tables in `db/extensions/`, hook near
  `Character.levelUp`/`gainExp` for EXP multipliers, reset logic around `saveCharToDB` concerns.
- **Account Legacy**: `accounts`-adjacent tables (`db/extensions/`), rate hooks in
  `Character.setPlayerRates`/`getExpRate` style getters (Character.java:4933+).
- **Achievements/Mastery**: event listeners around kill/boss/quest/level-up sites listed above.
- **QoL slots/storage (implemented 0.1)**: custom `coop:` config block (`coop/config/`,
  one-line hook in `config/YamlConfig.java`), slot defaults/caps in `Client`, `Character`,
  `Storage`, stack overrides in `coop/stack/` (+`ItemInformationProvider.getSlotMax`),
  solo-expedition sizing in `coop/expedition/` (+`ExpeditionType.getMinSize`), skill reset in
  `coop/reset/` (+`gm2/ResetSkillCommand`), GM cmds `!charslots`/`!storageslots` (gm4).
  Full touchpoint list: `docs/features/0.1-coop-qol.md`.
- **Companion Bot MVP (implemented 0.1b)**: new package `coop/companion/` with
  `coop-1100-companion-bindings` migration. Integration footprint is three
  touchpoints only: `CommandsExecutor` registers `@companion` (gm0);
  `Client.disconnectInternal` dismisses companions owned by the departing
  character before map/party teardown; `Server.shutdownInternal` calls
  `CompanionController.shutdownAll()` before worlds dispose their maps.
  Companions are real `Character` objects hosted by a headless `Client`
  subclass and are deliberately NOT in `PlayerStorage`. Damage/pickup reuse
  `MapleMap.damageMonster` / `Character.pickupItem`.
  Full contract: `docs/features/companion-party-bot-mvp.md`.

## Upstream quirks (do not "fix" silently)

- `mvnw` is committed without execute bit (mode 100644) → run `sh ./mvnw …`.
- Upstream `docker-compose.yml` uses an EMPTY MySQL root password and exposes 3306 →
  we use our own `ops/docker-compose.dev.yml` (see `docs/DECISIONS.md` D1).
- `DatabaseConnection` lives in `tools/`, not `database/`.
- `config.yaml` `DB_HOST` is overridden by the `DB_HOST` env var in Docker
  (`tools/DatabaseConnection.java:46-53`).
