---
description: Verify workspace, tools and baseline build/tests; report environment health.
---

Bootstrap/health check for MapleStory Co-op Remix.

1. Read `AGENTS.md` and `docs/DEPLOYMENT.md`.
2. Verify: git remotes (origin fork, upstream P0nk/Cosmic), current branch, working tree
   status, divergence vs `development`.
3. Verify toolchain: Java 21 via SDKMAN (`ops/_common.sh` resolves it), Docker available
   (use `sg docker -c` fallback if group is missing in this session), gh auth.
4. Run `ops/build.sh` (compile + tests) and `ops/status.sh`.
5. Report concisely: what is healthy, what is broken (classify: upstream | environment |
   our change), and the exact next step to fix anything broken.

Do not fix gameplay code here. Do not push anything.

Additional instructions (if any): $ARGUMENTS
