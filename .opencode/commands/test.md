---
description: Run automated tests (full suite or targeted) and analyze failures. Usage; /test [class or filter]
---

Run tests for MapleStory Co-op Remix.

- If a target is given: `sh ./mvnw -B test -Dtest=<target>` (Java 21 via SDKMAN; never bare mvn).
- Otherwise run the full suite via `ops/test.sh`.
- On failures: analyze each failure, classify (upstream issue | environment issue | our
  change), and report the root cause with file:line. Fix only if the cause is clearly our
  change or environment; otherwise report and propose next steps.
- Do NOT weaken or delete tests to make them pass. Do NOT change gameplay behavior just to
  satisfy a test without flagging it.

Target/filter (if any): $ARGUMENTS
