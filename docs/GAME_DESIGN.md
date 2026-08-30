# Game Design — MapleStory Co-op Remix

Cosmic v83 is our **stable technical foundation**, not a museum. We are building our own
MapleStory designed primarily for ~1–10 friends. This document is the binding design
reference; technical implications live in `ROADMAP.md`, per-feature specs in `features/`.

## Core pillars

1. **Solo-first co-op.** The game is fully playable solo. Group play is useful and fun, but
   never mandatory. No content is gated behind "bring N players".
2. **Fixed boss difficulty.** A boss has ONE consistent difficulty. No dynamic scaling down
   with fewer players. Challenge comes from gearing/rebirthing into it, not from party size.
3. **Progression beats coordination.** Character progression must eventually make old group
   bosses soloable. Raid boss → small party → duo challenge → solo challenge → farm boss.
4. **MapleStory feel over v83 purity.** Preserving the feel matters; preserving historical
   v83 exactness does not. Custom content is welcome; later MapleStory content may be
   selectively backported.
5. **Quality over quantity.** Fewer, better classes/features beat many shallow ones.

## Classes

- Long-term target: ~15–20 GOOD classes (existing rebalanced + created/backported later).
- Many character slots; enough to comfortably play every supported class. Alts encouraged.
- Class identity must stay readable: each class should feel distinct and have a mastery track.

## Leveling

- Level cap: **200** (Cygnus cap 120 is upstream — to be revisited in class rebalance).
- First run (RB0) targets: **1–120 in ~10–30 h**, **1–200 in ~30–60 h**.
- Leveling must stay engaging: quest/PQ/event EXP meaningful, not pure grind kill loops.

## Rebirth (core character loop)

- Character-specific; **class never changes** through Rebirth.
- Grants permanent character power; repeated Rebirths make the character dramatically faster
  to level and eventually strong enough to solo old raid bosses.
- Approximate 1–200 time targets:

| Rebirths | Target 1–200 time |
|---|---|
| RB0 | 30–60 h |
| RB1 | 15–20 h |
| RB2 | 10–15 h |
| RB3 | 7–10 h |
| RB5 | 4–6 h |
| RB10+ | 2–5 h |

- Rebirth resets level/EXP (and defines what else resets — spec in `features/` when built);
  power must be permanent and cumulative.

## Account Legacy (account loop)

- Every Rebirth also contributes account-wide progress ("Legacy").
- Character-specific power must be **substantially stronger** than account-wide power.
- Legacy affects every character on the account and may improve: EXP, drop rate, meso, boss
  damage, crit, HP, inventories, storage, travel, boss token gain, other utility.
- Account-wide **combat** bonuses use sensible caps to avoid runaway scaling.

## Class Mastery (mastery loop)

- Per supported class: progression/ranks driven by levels, Rebirths, boss kills, solo boss
  kills, gear, challenges, achievements.
- Rewards: titles, cosmetics, Legacy, account unlocks, small bonuses (no large combat power).

## Bossing (primary endgame)

- One difficulty per boss; natural progression: raid → small party → duo → solo → farm.
- Later: Ascended bosses (harder variants), challenge variants, custom bosses, imported bosses.
- Boss entries/quotas (upstream `bosslog` system) to be redesigned around tokens, not arbitrary
  daily lockouts, where design allows.

## Loot

- More generous than original old MapleStory.
- Exciting random drops + **deterministic bad-luck protection**.
- Boss tokens/materials as parallel currency; rare shared chase drops for groups.

## Itemization (long-term)

- Base item + **Affixes** + redesigned Scrolls + boss enhancements.
- We do NOT need to recreate later official Potentials exactly; a custom, mostly-server-side
  system is desirable (no client-side UI dependency).

## Achievements (long-term)

- Major system, eventually hundreds: progression, bosses, solo challenges, speed kills,
  Rebirth, classes, mastery, equipment, collections, cosmetics, unusual challenges.
- Reward hooks: titles, cosmetics, Legacy, small utility.

## Quality of life

Strong bias toward QoL:

- Many character slots (upstream default 3, cap 15 — to be raised)
- Bigger inventory/storage (upstream storage cap 48 slots), better stack sizes
- Fast/short travel (world `travel_rate` exists; consider permanent fast-travel unlocks)
- Convenient AP reset (exists: `AssignAPProcessor.APResetAction`) and Skill reset
- Remove/redesign HP-washing dependency
- Pets through gameplay; NX through gameplay
- Fewer arbitrary minimum party-size requirements (upstream examples: party cap 6, expedition
  minimums like Zakum min 6, PQ script minimums) — relax/remove where design allows

## Out of scope (for now)

- Client development/patching (server-side only)
- Public-server scale concerns (anti-DDoS, sharding)
- Faithful vanilla v83 preservation
