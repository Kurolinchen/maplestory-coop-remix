# Architecture Decisions

Format: context → decision → consequences. Append new decisions at the bottom. Never edit a
superseded decision; add a new one referencing it.

## Template

```
## D{n} — Title (YYYY-MM-DD, status: accepted|superseded by D{m})
Context: …
Decision: …
Consequences: …
```

## D1 — Own dev compose file instead of modifying upstream docker-compose.yml (2026-08-30, accepted)

Context: Upstream `docker-compose.yml` runs MySQL with an empty root password
(`MYSQL_ALLOW_EMPTY_PASSWORD: yes`) and exposes the DB port on all interfaces. Our baseline
requirements forbid empty DB passwords and want the DB not unnecessarily exposed.
Decision: Keep the upstream file untouched; run our dev environment from
`ops/docker-compose.dev.yml` (strong password from gitignored `ops/.env`, port bound to
`127.0.0.1`, named volume, healthcheck, rendered config with injected password).
Consequences: Upstream merges stay conflict-free; agents must use `ops/*.sh` (never raw
`docker compose up` on the upstream file for dev).

## D2 — Custom code in own packages + Liquibase extensions (2026-08-30, accepted)

Context: We fork Cosmic long-term and must keep merging upstream feasible.
Decision: Custom systems live in their own Java packages (root package `coop` reserved for
us) and their own Liquibase changesets under `src/main/resources/db/extensions/`
(auto-included by `changelog-root.xml`). Modifications to existing Cosmic classes must be
minimal, centralized and documented in `docs/ARCHITECTURE.md` §Integration points.
Consequences: Clean diffs vs upstream; custom migrations never clash with upstream ids
(upstream tables 1-24, data 101-161).

## D3 — Project documentation in English (2026-08-30, accepted)

Context: Development is heavily LLM-assisted; human owner communicates in German.
Decision: All repo docs, commit messages and code stay English; chat with the owner is German.
Consequences: Best LLM comprehension; owner accepts English docs.

## D4 — Java via SDKMAN, Amazon Corretto 21 (2026-08-30, accepted)

Context: Cosmic requires Java 21; upstream CI and Dockerfile use Amazon Corretto 21.
System-wide installs would need sudo.
Decision: Use SDKMAN user-local JDK `21.0.12-amzn` (SDKMAN default). `ops/_common.sh`
resolves `JAVA_HOME` from SDKMAN so no shell setup is required.
Consequences: Matches upstream CI; no sudo; every agent must build via `ops/build.sh` or with
SDKMAN-resolved `JAVA_HOME` (plain `mvn`/system Java 8 must not be used).

## D5 — ops/ scripts are the single command surface (2026-08-30, accepted)

Context: Future LLM agents should not re-derive raw Maven/Docker/MySQL commands.
Decision: All environment operations go through `ops/*.sh` (build, start, stop, logs, tests,
smoke test, DB backup/restore/reset, status). Destructive scripts require interactive
confirmation.
Consequences: One place to change behavior; permission rules can target `ops/…` paths.

## D6 — mvnw invoked via `sh ./mvnw` (2026-08-30, accepted)

Context: Upstream commits `mvnw` without the executable bit (mode 100644).
Decision: Never chmod it; run `sh ./mvnw …` (wrapped by ops scripts).
Consequences: No diff against upstream file modes.

## D7 — HP-wash redesign deferred from 0.1 to 0.2; own `coop:` config block (2026-08-30, accepted)

Context: ROADMAP 0.1 lists "remove/redesign HP-wash dependency", but the mechanic spans
level-up HP/MP rolls (Character.levelUp), job-change rolls, AP-reset math
(AssignAPProcessor) and persisted `hpMpApUsed` state, gated by three live flags. Changing
curves without the data-driven progression framework (0.2) would be unreviewable guesswork.
Additionally, milestone 0.1 introduces the first custom config numbers and must not scatter
them into upstream-owned `ServerConfig`/`WorldConfig` (merge friction on every upstream sync).
Decision (owner-approved): HP-wash stays untouched in 0.1 and is redesigned data-driven in
0.2. Custom QoL numbers live in a new top-level `coop:` block in `config.yaml`, parsed into
`coop/config/CoopConfig` (one added field in `config/YamlConfig.java`), accessed via
null-safe `coop/config/CoopDefaults`.
Consequences: 0.1 stays reviewable; 0.2 owns the HP/MP curve redesign; future custom systems
add keys to the `coop:` block instead of upstream config classes.

## D8 — Solo expeditions default-on incl. Zakum prequest bypass (2026-08-30, accepted)

Context: GAME_DESIGN pillar 1 (solo-first) requires no content gated behind party minimums.
Upstream `USE_ENABLE_SOLO_EXPEDITIONS` also bypasses Zakum prequests when enabled.
Decision (owner-approved): set `USE_ENABLE_SOLO_EXPEDITIONS: true`; expedition minimum sizes
become configurable per type via `coop.expedition_min_size`. Party cap stays 6 (client UI).
Consequences: Any boss expedition is solo-enterable; prequest gating is off; PQ script
`minPlayers` relaxation is a documented per-script follow-up (see 0.1 feature spec).

## D9 — Companion Bots are real characters with opt-in everything (2026-09-01, accepted)

Context: Milestone 0.1b wants optional Companion Bots for the first gameplay
test. The owner's rules were: companions optional, solo always viable, no
fork merge, no external bulk copy, and no advanced autonomy (ownerless
population, economy automation, RTS control).

Decision (owner-approved):
- A companion is a NORMAL alternate character, loaded/saved through the existing
  Character paths and hosted by a headless `Client` subclass whose `sendPacket`
  is a no-op. It is NOT registered in `Channel`/`World` `PlayerStorage`.
- No code was ported from `nutnnut/Cosmic` (pinned `b684bf7858d5`) or
  `NDBellisario/cosmic` (pinned `b01cf27833f5`). Inspecting
  `nutnnut/Cosmic:src/main/java/server/bots` showed a full artificial-population
  system (`BotGenerator`, `BotGachaponManager`, `BotFarmingCostModel`, ~50
  classes) — exactly the autonomy we defer. The references are recorded for
  provenance only; `P0nk/Cosmic` remains our upstream lineage.
- Everything dangerous defaults to off: feature disabled, empty map allowlist
  (meaning NO map is eligible), portal fallback disabled, looting opt-in,
  death dismisses the companion.
- Damage comes from the companion's real stats through the upstream
  `calculateMaxBaseDamage`/`calculateMaxBaseMagicDamage` helpers and is clamped
  by both a monster-HP fraction and an absolute ceiling.
- All damage and pickup go through `MapleMap.damageMonster` and
  `Character.pickupItem` so the existing EXP/loot/ownership pipelines stay
  authoritative.

Consequences: Solo play is unchanged when companions are disabled. A companion
can never satisfy a party-size check, because instanced map ranges are
hard-blocked. Cross-map travel is limited to following the owner through
scriptless allowlisted portals; the catch-up fallback stays inert until its own
audit signs off.

## D10 — Companion state lives in one binding table, not a parallel character schema (2026-09-01, accepted)

Context: A companion must remain a fully playable normal character, so any
"bot schema" risks diverging from `characters` and creating duplicate sources of
truth for level, inventory, equipment and EXP.

Decision: The only new persisted state is `coop_companion_bindings`
(owner PK, companion UNIQUE, both FK `ON DELETE CASCADE`). Level, EXP, HP/MP,
inventory, equipment, mesos and map all stay in the existing character tables.
Denormalised `account_id`/`world` on the binding are for audit speed only;
runtime ownership always re-verifies against `characters`.

Consequences: Logging into a companion manually is always possible and always
sees the same state. Character deletion cleans up bindings automatically. The
companion system can be removed later by dropping one table without leaving
orphaned character state.
