---
description: Show dev environment status - git, containers, logs, backups. Usage; /status-dev
---

Report the current development status of MapleStory Co-op Remix. Be concise.

1. Git: branch, clean/dirty, divergence vs origin and vs `development`, last 3 commits.
2. Environment: run `ops/status.sh` (containers, DB volume, last backup, rendered config).
3. Server health: if running, tail `ops/logs.sh server` for errors/warnings since boot and
   confirm whether "Cosmic is now online" was reached.
4. Note anything broken, classified as upstream | environment | our change, and the next
   action to fix it.

Do not change anything; read-only status report.

Extra focus (if any): $ARGUMENTS
