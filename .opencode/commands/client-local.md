---
description: Verify and launch the local Cosmic client. Usage; /client-local
---

1. Run `ops/client-status.sh`. If client integrity fails, diagnose it without bypassing or
   regenerating the manifest automatically.
2. If the server is unavailable, start it with `ops/start.sh` and wait for
   "Cosmic is now online".
3. Run `ops/client-run-local.sh`. Its full runtime hash verification is mandatory.
4. Confirm the client process starts and inspect server logs for a login connection.

Never add `.local/` assets to Git, install random Winetricks dependencies, or weaken hash
verification.

Extra instructions (if any): $ARGUMENTS
