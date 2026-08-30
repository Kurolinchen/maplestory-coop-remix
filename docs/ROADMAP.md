# Roadmap — MapleStory Co-op Remix

Versioning intent: milestones 0.x build the custom core; **1.0 = stable custom core game**.
Each milestone gets feature specs in `docs/features/` before implementation.

## 0.0 — Clean reproducible Cosmic baseline ✅ (bootstrap)

- Repo/branch model, docs framework, agents/commands, ops scripts, safe Docker dev env
- Build + all upstream tests green; smoke test boots to "Cosmic is now online"
- Exit: any agent can rebuild and boot the baseline with `ops/build.sh` + `ops/smoke-test.sh`

## 0.1 — Co-op / QoL foundation

- More character slots, bigger inventory/storage defaults, better stack sizes
- Relaxed party-size minimums (expeditions/PQs where sensible), solo expedition flag review
- Convenient AP/skill reset UX, travel QoL, remove/redesign HP-wash dependency
- Dev tooling: GM-command surface for testing, smoke tests extended
- Exit: alt-friendly server; a tester can create many characters and move fast

## 0.2 — Progression framework

- Config/data-driven rate & curve framework (EXP tables, level-bracket bonuses)
- Telemetry: EXP logs enabled by default in dev, balance dashboards/queries
- Exit: every future progression number lives in data, not code

## 0.3 — Rebirth

- Character-specific rebirth; class locked; permanent power; dramatically faster leveling
- Targets per `GAME_DESIGN.md` table (RB0 30–60 h … RB10+ 2–5 h for 1–200)
- Exit: RB5 test character reaches 200 in 4–6 h of focused play

## 0.4 — Account Legacy

- Legacy accrues from Rebirths; capped account-wide bonuses (EXP/drops/meso/boss dmg/crit/HP/
  inventory/storage/travel/tokens); character power stays clearly dominant
- Exit: Legacy visible on fresh character; caps verified by tests/queries

## 0.5 — Class Mastery

- Per-class rank track (levels, rebirths, boss kills incl. solo kills, gear, challenges)
- Rewards: titles, cosmetics, Legacy, unlocks; no large combat bonuses
- Exit: two classes with full mastery tracks as reference implementation

## 0.6 — Class rebalance

- Rebalance toward 15–20 good classes; Cygnus level-cap decision; skill/QoL fixes
- Exit: every supported class viable solo and in group through 200

## 0.7 — Boss progression

- One fixed difficulty per boss; token/material economy; bad-luck protection
- Progression ladder raid → party → duo → solo → farm; entry redesign vs upstream bosslogs
- Exit: Zakum/Horntail/PinkBean ladder playable and measurable

## 0.8 — Loot / Affixes / Scroll redesign

- Server-side affix system on base items, redesigned scrolls, boss enhancements
- Generous drops + deterministic protection + shared chase drops
- Exit: affix pipeline with tests; old scroll RNG pain removed

## 0.9 — Achievements + Ascended bosses

- Achievement framework (progression/bosses/solo/speed/rebirth/mastery/collections)
- Ascended/challenge boss variants
- Exit: 50+ achievements live; first Ascended boss shipped

## 1.0 — Stable custom core game

- Hardening, regression suite, deployment maturity, documentation completeness

## Post-1.0 (unordered)

Additional/custom classes, backported content, new maps/monsters/bosses, larger expansions.

## Dependency notes

- Progression framework (0.2) before Rebirth (0.3): Rebirth multipliers must be data-driven.
- Legacy (0.4) after Rebirth (0.3): Legacy accrues from rebirth events.
- Class Mastery (0.5) after Legacy: mastery rewards feed into Legacy.
- Boss progression (0.7) after Rebirth: solo-ability targets depend on rebirth power curve.
- If technical analysis contradicts this order, document the change in `DECISIONS.md` first.
