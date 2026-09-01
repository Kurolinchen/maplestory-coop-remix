---
description: Verify or build a client endpoint profile. Usage; /client-build <profile> [template-dir]
---

Accept only `local-dev`, `vps-dev` or `vps-prod`, then run
`tools/client/build-client-profile.sh <profile> [template-dir]` with the arguments supplied
in `$ARGUMENTS`. Report the output directory and verification result.

Do not invent endpoint IPs. Empty remote profile IPs must remain a hard failure. Never stage
or commit `.local/` client binaries, WZ files, installers, archives or generated packages.
