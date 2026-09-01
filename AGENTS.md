# AGENTS.md — Rules for every coding agent in this repository

This repository is **MapleStory Co-op Remix**, a fork of [P0nk/Cosmic](https://github.com/P0nk/Cosmic)
(MapleStory Global v83 server emulator, Java 21). Development here is heavily LLM-assisted.
Humans decide game design, approve sensitive actions, and playtest. Agents do the technical work.

## Read first

Before changing anything, read the docs relevant to the task:

- `docs/GAME_DESIGN.md` — what we are building and why (Rebirth, Account Legacy, Class Mastery, bossing, loot)
- `docs/ARCHITECTURE.md` — where things live in the Cosmic codebase (verified file:line references)
- `docs/ROADMAP.md` — milestone order and current focus
- `docs/DECISIONS.md` — binding architecture decisions (append new ones; never silently break old ones)
- `docs/DEPLOYMENT.md` — local dev environment and future VPS layout
- `docs/TESTING.md` — how to build, test, smoke-test
- `docs/features/` — one spec per implemented/planned feature

## Hard rules

1. **Inspect actual code; never guess.** Class names and locations in this codebase are
   non-obvious (e.g. `DatabaseConnection` lives in `tools/`, not `database/`). Grep and read
   before claiming where something is. Cite `file:line` in plans and reviews.
2. **Minimize Cosmic-core modifications.** Custom systems (Rebirth, Legacy, Class Mastery,
   Achievements, Affixes…) must be modular: own packages (e.g. under a `coop` root package),
   own Liquibase migrations, own config. Touch existing classes only where integration is
   unavoidable; keep such touchpoints tiny and documented.
3. **Prefer server-side solutions.** The client is a fixed v83 binary; we ship no client
   patches. Anything achievable with packets/scripts/config stays server-side.
4. **Reproducible migrations only.** Schema/data changes go through Liquibase
   (`src/main/resources/db/`; custom changesets go into `src/main/resources/db/extensions/`
   which is auto-included — see `docs/ARCHITECTURE.md` §Database). Never hand-edit a live dev
   DB as a shortcut. Never edit an already-committed changeset; add a new one.
5. **Keep balance configurable.** Numbers (EXP curves, Rebirth bonuses, drop rates, boss
   tuning) belong in config/data files or DB tables, not hardcoded. Balance changes should be
   reviewable as pure data changes wherever possible.
6. **Write tests where practical.** JUnit 5 + Mockito under `src/test/java`; follow the
   existing `testutil.HandlerTest` pattern for packet handlers (see `docs/TESTING.md`).
7. **Verify before calling work done.** At minimum: `ops/build.sh` compiles and tests pass;
   for gameplay-affecting features also run `ops/smoke-test.sh` and list the manual in-game
   test steps that remain for the human.
8. **Never deploy automatically.** Dev deployment requires explicit human approval;
   production deployment ALWAYS requires explicit human approval.
9. **Never commit secrets.** No passwords, API keys or tokens in code, config or docs.
   `.env` files are gitignored; only `.env.example` is committed.
10. **Document significant decisions.** Anything that constrains future work goes into
    `docs/DECISIONS.md`.
11. **Never rewrite published history.** No force-push, no `git reset --hard` on shared
    branches, no destructive `git clean` without explicit approval.

## Branch model

- `master` — stable code
- `development` — integration branch; feature work lands here first
- `feature/<name>` — branch off `development`, merge back into `development` via PR

Work on a feature branch unless the task is explicitly docs/config/ops-only.

## Git remotes

- `origin` = https://github.com/Kurolinchen/maplestory-coop-remix.git (our fork)
- `upstream` = https://github.com/P0nk/Cosmic.git (read-only; push URL intentionally disabled)

Upstream sync: use the `/sync-upstream` command (fetch + merge `upstream/master` into a fresh
branch off `development`; never rebase or force).

## Daily commands

Everything is wrapped in `ops/` so nobody has to remember raw commands:

```
ops/build.sh            # full Maven build incl. tests (Java 21 via SDKMAN)
ops/test.sh             # unit tests only
ops/start.sh            # start dev DB + server (Docker)
ops/stop.sh             # stop dev environment
ops/restart.sh          # restart
ops/logs.sh [server|db] # tail logs
ops/status.sh           # status of containers, volumes, backups, git
ops/smoke-test.sh       # boot server, wait for "Cosmic is now online", verify, stop
ops/backup-dev-db.sh    # dump dev DB to ops/backups/
ops/restore-dev-db.sh   # restore a dump (asks for confirmation)
ops/reset-dev-db.sh     # DESTRUCTIVE: wipe dev DB volume (double confirmation)
ops/rotate-dev-db-pass.sh # rotate dev DB root password (see docs/DEPLOYMENT.md)
```

## Game design north star (short form)

Solo-first co-op for ~1–10 friends. Bosses have one fixed difficulty (no party-size scaling).
Progression loops: level cap 200 → Rebirth (character loop) → Account Legacy (account loop) →
Class Mastery (mastery loop). Old raid bosses must eventually become soloable.
Full design: `docs/GAME_DESIGN.md`.
