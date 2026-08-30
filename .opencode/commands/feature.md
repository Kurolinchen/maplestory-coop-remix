---
description: Full feature workflow - plan, spec, implement, migrate, test, review, document. Usage; /feature <description>
---

Implement the following feature end-to-end:

$ARGUMENTS

Follow this workflow strictly, in order:

1. **Context** — read `AGENTS.md`, `docs/GAME_DESIGN.md`, `docs/ROADMAP.md`,
   `docs/ARCHITECTURE.md`, `docs/DECISIONS.md`; locate the milestone this belongs to.
2. **Architecture** — delegate a read-only plan to the `architect` subagent. If the request
   conflicts with `GAME_DESIGN.md`, STOP and ask the owner before proceeding.
3. **Spec** — create/update `docs/features/<milestone>-<slug>.md` (template in
   `docs/features/README.md`). Work on `feature/<slug>` branched off `development`.
4. **Implement** — minimal Cosmic-core footprint (own packages, tiny documented touchpoints,
   server-side only). No hardcoded balance numbers — config/data.
5. **Migrations** — if schema/data changes are needed, add Liquibase changesets under
   `src/main/resources/db/extensions/` (never edit existing changesets).
6. **Tests** — add JUnit tests where practical (handler pattern per `docs/TESTING.md`).
7. **Verify** — run `ops/build.sh`; run `ops/smoke-test.sh` if startup/DB/scripts touched.
8. **Review** — delegate to the `reviewer` subagent; if migrations changed, also to
   `database-reviewer`.
9. **Repair** — fix all critical/major findings; re-run build/tests.
10. **Docs** — update `docs/ARCHITECTURE.md` (integration points), `docs/DECISIONS.md` if a
    binding decision was made, and the feature spec's test results.
11. **Handoff** — present: summary of changes, `git status`/diff stat, and the **manual
    gameplay test steps** for the owner. Do NOT commit/push/deploy without explicit approval.

Never deploy. Never touch `master`. Never force-push.
