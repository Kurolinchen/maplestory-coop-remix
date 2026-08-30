---
description: Fetch upstream (P0nk/Cosmic) and merge it into development safely. Never rebase, never force-push.
---

Sync upstream Cosmic into this fork safely.

1. `git fetch upstream` and `git fetch origin`.
2. Report divergence: how many commits upstream/master is ahead of `development`, and list the
   incoming commits (`git log --oneline development..upstream/master`).
3. If nothing new: report and stop.
4. If there are changes: create branch `chore/sync-upstream-<YYYYMMDD>` from `development`,
   then `git merge upstream/master`.
   - Resolve conflicts favoring OUR custom code (`coop` packages, `db/extensions/`, docs, ops,
     .opencode) and UPSTREAM for untouched Cosmic files.
   - After resolving, run `ops/build.sh`; fix compile/test breakage caused by the merge only.
5. Run `ops/smoke-test.sh` if migrations/scripts changed.
6. Present the merge result + diff stat. Do NOT push to `development` without owner approval.

Never rebase published history. Never force-push. Never push to upstream.

Notes: $ARGUMENTS
