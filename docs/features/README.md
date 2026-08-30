# Feature specs

One file per feature: `docs/features/<milestone>-<slug>.md` (e.g. `0.3-rebirth.md`).
Created by the `/feature` workflow before implementation; updated with test results after.

## Template

```markdown
# <Feature> (Milestone <x.y>)

Status: planned | in progress | implemented | verified by playtest
Branch: feature/<name>

## Goal
What player-facing problem this solves; link to docs/GAME_DESIGN.md section.

## Design
Mechanics, numbers, caps. All tunable numbers listed with their config/data location.

## Cosmic touchpoints
Existing classes touched (file:line) and why each touch is unavoidable.
New packages/tables introduced.

## Migrations
db/extensions changesets added (schema + data).

## Tests
Automated tests added; smoke-test relevance.

## Manual test steps
1. GM setup commands
2. in-client steps
3. expected results
4. data/log checks

## Balance assumptions
Data sources used (logs/queries), assumptions made, risks.

## Playtest feedback
(filled by owner after testing)
```
