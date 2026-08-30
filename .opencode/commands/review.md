---
description: Independent code review via the reviewer agent. Usage; /review [branch|commit range|path...]
---

Perform an independent code review.

- Scope: $ARGUMENTS (default: uncommitted changes + commits of the current branch not in
  `development`).
- Delegate to the `reviewer` subagent (read-only). If the diff contains schema/SQL/persistence
  changes, also delegate those parts to `database-reviewer`.
- Collect findings, then fix critical and major issues yourself (on the same feature branch),
  re-run `ops/build.sh`, and re-request review only for the fixed parts.
- Report final verdict and remaining minor/nit findings. Do not commit/push without approval.
