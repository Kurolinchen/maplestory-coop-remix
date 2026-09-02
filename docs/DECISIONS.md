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

## D11 — Early Game Remix strengthens beginner skills, kits and KPQ entry as data (2026-09-01, accepted)

Context: Levels 1-30 are the least fun part of a solo/small-group server.
Upstream v83 spreads early-game balance across JavaScript job scripts and
assumes a large population. GAME_DESIGN pillar 1 (solo-first) requires that no
content is gated behind party size, and "leveling must stay engaging" argues
against a pure grind slog.

Decision (owner-approved):
- **Ultra Beginner Skills are enabled**, i.e. the three existing server-side
  flags `USE_ULTRA_NIMBLE_FEET`, `USE_ULTRA_RECOVERY` and
  `USE_ULTRA_THREE_SNAILS`. No new skill was invented; the flags already
  existed and were off. Beginner jobs additionally get a configurable SP bonus
  at creation (`coop.early_game.beginner_sp_bonus`) so the three skills are
  actually usable in levels 1-10.
- **First-job kits are data, not script edits.** A `coop_first_job_kits` table
  plus one hook at the end of `Character.changeJob` replaces editing five NPC
  scripts and five Cygnus quest scripts.
- **`@training` reports, never teleports.** It is a player command listing
  level-appropriate maps derived from the map WZ; adding a teleport would turn
  it into a travel feature with skip potential.
- **Telemetry is opt-in and adjacent.** Instead of widening upstream
  `characterexplogs` (which records no level/map/job), a coop table is added;
  it is off by default and fully asynchronous.
- **KPQ becomes solo-enterable** (`minPlayers` 3 -> 1), the documented D8
  follow-up for PQ script minimums.

Consequences: Every number above lives in the `coop:` config block or in a
database table, so early-game balance is a pure data change with no code edit
and no client patch. The beginner-skill strengthening is intentionally strong
but confined to skills that stop being used after the second job. `@training`
cannot be abused for travel. Telemetry costs nothing while disabled. KPQ remains
playable by groups of up to 4. Full spec:
`docs/features/0.1b-early-game-remix.md`.

## D12 — Early-game playtest boundaries are explicit (2026-09-01, accepted)

Context: Release review found that lowering KPQ's entry minimum alone did not
make its simultaneous-position puzzles soloable, generic EXP telemetry could
not truthfully identify an award source, and arithmetic first-job detection
accepted unrelated job IDs.

Decision: KPQ stages 2-4 require `max(0, party size - 1)` occupied positions, so
a solo leader confirms at Cloto while staying off every puzzle position.
Telemetry records only positive EXP accepted at pre-award levels 1-30 and labels
the generic pipeline `UNATTRIBUTED`; it remains disabled in Java defaults but is
enabled in the checked-in configuration for this owner-approved online
playtest. First-job kits use the exact Explorer, Cygnus and Aran advancement IDs.

Consequences: Solo KPQ is mechanically completable without a client patch,
telemetry cannot grow from levels 31-200 or claim false MOB attribution, and
future job IDs must be deliberately added to the kit policy. Telemetry batching
is transactional and receives a bounded shutdown drain while the DB is live.

## D13 — Registration is an isolated service; the game JVM serves no HTTP (2026-09-02, accepted)

Context: Friends need accounts without the owner creating them by hand, and the
public upstream seed credential (neutralized by the `coop-1230` changeset, see
`db/extensions/coop-1230-security-hardening.xml`) showed that anything reachable
from the internet must be auditable. Embedding a web server in the game JVM
would expose the process that holds WZ data, scripts and the game DB credential
to the internet, and every HTTP dependency would sit in the same memory space as
live gameplay.

Decision (owner-approved): Account registration is a **separate JVM process and
a separate container** (`src/main/java/coop/registration`,
`Dockerfile.registration`), using only the JDK `com.sun.net.httpserver` and
HikariCP (already a project dependency). The game server keeps zero HTTP
listeners; the registration container has no WZ, no game scripts, no game config and
no additional operational packages. The base JRE image retains minimal shell utilities for its
healthcheck. It reaches MySQL only over an internal Docker network as a
least-privilege user and never receives the game DB root credential. Secrets
come from **files** (`REG_DB_PASSWORD_FILE`, `REG_INVITE_FILE`), never from
argv. Caddy terminates TLS on ports 80/443 and is the only published edge
(`ops/Caddyfile.vps`); it replaces client-supplied `X-Forwarded-For`, and the app
accepts that header only from explicitly configured proxy IPs.

Consequences: An HTTP-level vulnerability can no longer reach the game process
or the root DB credential; the registration service can be restarted, scaled or
removed independently. Operational cost: one more image, one more secret pair
(DB password, invite passphrase) and one more compose service that ops must
generate. The app stores bcrypt hashes with the same `tools.BCrypt` cost as the
login path, so accounts created there are indistinguishable from
`AUTOMATIC_REGISTER` accounts.

## D14 — Least-privilege DB users are operational provisioning, not Liquibase (2026-09-02, accepted)

Context: The registration service must not be able to read or modify game state,
so it needs a MySQL account restricted to `INSERT` on `accounts`. Liquibase
changesets run with the same credential as the schema owner and are applied to
every database (local dev, VPS dev, future prod), and MySQL user management
(`CREATE USER`, `GRANT`) does not belong in a portable, checksummed migration.

Decision: The registration DB user is created by ops provisioning on the target
host (documented in `docs/DEPLOYMENT.md`), not by a changeset. Only *schema and
data* changes go through Liquibase (`db/extensions/`), as before (D2).

Consequences: A freshly migrated database is usable by the game server without
any extra user, and the registration container fails closed (503 on
`/health/ready`) until provisioning has run. Provisioning steps must be repeated
per environment and are not covered by `ops/smoke-test.sh`.

## D15 — AUTOMATIC_REGISTER stays true until separately approved (2026-09-02, accepted)

Context: With a registration app in place, the obvious hardening is to disable
`AUTOMATIC_REGISTER` so only pre-registered accounts can log in. Doing so is a
behaviour change for the running playtest: anyone whose account was created
implicitly by logging in would keep working (rows exist), but the client's
"create account" path would start failing, and the two account sources would
behave differently until both are verified.

Decision: `AUTOMATIC_REGISTER: true` remains in `config.yaml`. Turning it off is
a separate, owner-approved change with its own playtest, and it requires that
the registration app has been used successfully end-to-end first.

Consequences: While the flag is true, the login server still creates accounts on
demand, so the registration app is a *convenience and access-control* layer for
invited players, not the only account source. Anyone who can reach the login
port can still create an account; the invite passphrase therefore limits the
*web* path only. The flag flip is the residual risk tracked in
`docs/features/security-registration-and-public-handbook.md`.

## D16 — No external HTML template by default; the handbook PDF is not published (2026-09-02, accepted)

Context: The registration pages are small and security-relevant (CSRF token,
error text). Rendering them from a mounted template file would let a writable
volume or a careless redeploy change security-relevant markup, and it would
reintroduce a template engine dependency. The German handbook
(`docs/handbook/`, built by `ops/build-handbook.sh`) contains installation
details and the full command list for a private server.

Decision: Page HTML is generated in Java (`coop/registration/Pages.java`) and
ships inside the jar; only static assets (`index.html`, `assets/register.css`)
come from `REG_RESOURCE_DIR`, mounted read-only. The handbook PDF is **not**
copied into the registration image, so `/handbook.pdf` answers 404 until the
owner deliberately publishes it.

Consequences: Markup changes require a rebuild and a Java change (reviewable in
a PR) instead of a file drop, and there is no template-injection surface. The
PDF link is omitted from `index.html` until publishing a reviewed PDF is approved
as a separate decision.

## D17 — DEV web integration: fixed private networks, exact-host DB grant, pinned edge (2026-09-02, accepted)

Context: The registration service (D13) only trusts exact proxy IPs and needs a
deterministic MySQL source address for a least-privilege grant. The existing
`maple-dev-net` assigns dynamic addresses, so trusting it would mean trusting a
whole subnet, and the documented D14 grant with host `%` would work from any
Docker network.

Decision (owner-approved, DEV only): Bootstrap creates two additional DEV-only
networks whose subnets are validated on every run (refuse, never recreate):
`maple-dev-web-net` (`172.30.250.0/24`; caddy `172.30.250.2`, registration
`172.30.250.3`) and the Docker-internal `maple-dev-registration-db-net`
(`172.30.251.0/29`; db `172.30.251.2`, registration `172.30.251.3`). The DB
grant is bound to the exact host `registration@172.30.251.3` with only
`INSERT` on `cosmic.accounts`; deploy provisioning re-applies the password from
the secret file and hard-verifies `mysql.user`/`tables_priv`/`db` afterwards.
Caddy is pinned by digest (`caddy:2.10.2-alpine@sha256:4c6e91c6…618e530d`),
publishes only 80/443, and starts only after registration reports healthy.
Registration secrets are generated once on the VPS by
`ops/provision-vps-registration.sh` and are never printed, logged or rotated as
a side effect. `AUTOMATIC_REGISTER` stays untouched (D15).

Consequences: Spoofed `X-Forwarded-For` cannot widen the rate-limit key, the
registration service cannot reach MySQL from any other network, and a public
web incident cannot reach the game JVM or the root DB credential. Operational
cost: two more networks with fixed subnets (collision is checked at bootstrap),
a pinned Caddy digest to bump deliberately, and a deploy order (game → DB user →
registration → Caddy) that must not be reordered. Production stays unconfigured;
replicating this there is a new, separately approved decision.
