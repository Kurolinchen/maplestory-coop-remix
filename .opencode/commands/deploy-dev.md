---
description: Deploy the dev environment. Local = Docker compose; remote VPS only when configured AND owner-approved.
---

Deploy the DEV environment (never production).

Interpret the target from $ARGUMENTS (default: local).

**Local deploy:**
1. `ops/build.sh` (fail fast on build/test errors).
2. `ops/start.sh` then `ops/status.sh`; verify server reaches "Cosmic is now online"
   (`ops/logs.sh server`).
3. Report result + how to connect (login port 8484, channels 7575-7577).

**Remote (VPS) deploy:**
- Only if `ops/verify-vps.sh` reports the VPS as configured AND reachable AND the owner has
  given explicit approval in this conversation. Otherwise STOP and say exactly what is missing
  (VPS host, bootstrap run, approval).
- If cleared: run `ops/deploy-dev.sh` and then `ops/dev-status.sh`.

Never deploy to production. Never touch secrets except to confirm they exist. Report what was
deployed and from which commit.

Target/instructions: $ARGUMENTS
