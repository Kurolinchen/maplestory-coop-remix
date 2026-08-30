---
description: Systematic debugging - repro, root cause, smallest fix, regression test. Usage; /debug <bug description>
---

Debug and fix the following problem:

$ARGUMENTS

Delegate root-cause analysis to the `debugger` subagent, then follow its report:

1. Understand the bug; inspect the actual implementation (docs/ARCHITECTURE.md first).
2. Reproduce where practical (targeted test, logs via `ops/logs.sh server`, dev DB query).
3. Identify the root cause with file:line and classify: upstream | environment | our change.
4. Implement the smallest correct fix (gameplay numbers stay config/data-driven).
5. Add a regression test (failing first where practical).
6. Have the `reviewer` subagent check the fix.
7. Verify: affected tests + `ops/build.sh`; `ops/smoke-test.sh` if startup/DB/scripts touched.

Report: symptom → repro → root cause → classification → fix → tests → manual in-game
verification steps for the owner. Do not deploy. Do not mask symptoms silently.
