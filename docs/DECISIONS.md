# Architecture Decisions

Format: context → decision → consequences. Append new decisions at the bottom. Never edit a
superseded decision; add a new one referencing it.

## Template

```
## D{n} — Title (YYYY-MM-DD, status: accepted|superseded by D{m})
Context: …
Decision: …
Consequences: …
```

## D1 — Own dev compose file instead of modifying upstream docker-compose.yml (2026-08-30, accepted)

Context: Upstream `docker-compose.yml` runs MySQL with an empty root password
(`MYSQL_ALLOW_EMPTY_PASSWORD: yes`) and exposes the DB port on all interfaces. Our baseline
requirements forbid empty DB passwords and want the DB not unnecessarily exposed.
Decision: Keep the upstream file untouched; run our dev environment from
`ops/docker-compose.dev.yml` (strong password from gitignored `ops/.env`, port bound to
`127.0.0.1`, named volume, healthcheck, rendered config with injected password).
Consequences: Upstream merges stay conflict-free; agents must use `ops/*.sh` (never raw
`docker compose up` on the upstream file for dev).

## D2 — Custom code in own packages + Liquibase extensions (2026-08-30, accepted)

Context: We fork Cosmic long-term and must keep merging upstream feasible.
Decision: Custom systems live in their own Java packages (root package `coop` reserved for
us) and their own Liquibase changesets under `src/main/resources/db/extensions/`
(auto-included by `changelog-root.xml`). Modifications to existing Cosmic classes must be
minimal, centralized and documented in `docs/ARCHITECTURE.md` §Integration points.
Consequences: Clean diffs vs upstream; custom migrations never clash with upstream ids
(upstream tables 1-24, data 101-161).

## D3 — Project documentation in English (2026-08-30, accepted)

Context: Development is heavily LLM-assisted; human owner communicates in German.
Decision: All repo docs, commit messages and code stay English; chat with the owner is German.
Consequences: Best LLM comprehension; owner accepts English docs.

## D4 — Java via SDKMAN, Amazon Corretto 21 (2026-08-30, accepted)

Context: Cosmic requires Java 21; upstream CI and Dockerfile use Amazon Corretto 21.
System-wide installs would need sudo.
Decision: Use SDKMAN user-local JDK `21.0.12-amzn` (SDKMAN default). `ops/_common.sh`
resolves `JAVA_HOME` from SDKMAN so no shell setup is required.
Consequences: Matches upstream CI; no sudo; every agent must build via `ops/build.sh` or with
SDKMAN-resolved `JAVA_HOME` (plain `mvn`/system Java 8 must not be used).

## D5 — ops/ scripts are the single command surface (2026-08-30, accepted)

Context: Future LLM agents should not re-derive raw Maven/Docker/MySQL commands.
Decision: All environment operations go through `ops/*.sh` (build, start, stop, logs, tests,
smoke test, DB backup/restore/reset, status). Destructive scripts require interactive
confirmation.
Consequences: One place to change behavior; permission rules can target `ops/…` paths.

## D6 — mvnw invoked via `sh ./mvnw` (2026-08-30, accepted)

Context: Upstream commits `mvnw` without the executable bit (mode 100644).
Decision: Never chmod it; run `sh ./mvnw …` (wrapped by ops scripts).
Consequences: No diff against upstream file modes.
