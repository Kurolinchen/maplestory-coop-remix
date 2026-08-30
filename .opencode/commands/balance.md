---
description: Data-driven balance analysis - no random tuning. Usage; /balance <what feels off>
---

Analyze the following balance issue:

$ARGUMENTS

Delegate to the `balance-analyst` subagent (read-only). Rules:

- No random tuning. Use telemetry (e.g. `characterexplogs`), config/data files and the design
  targets in `docs/GAME_DESIGN.md`. Every assumption must be written down.
- Propose pure data/config changes with quantified effect; respect caps for account-wide
  bonuses (Account Legacy) and rate-stacking flags.
- After analysis: present the recommendation. Apply changes ONLY after the owner approves.
  When applied: they must be data/config diffs, covered by `ops/build.sh`, and recorded in the
  relevant feature spec / DECISIONS if binding.
- Suggest which telemetry to collect afterwards and what manual playtest confirms the feel.
