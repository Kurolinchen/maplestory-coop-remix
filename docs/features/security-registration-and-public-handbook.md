# Security hardening, registration app and public handbook (Milestone 0.1b, security slice)

Status: implemented and integrated into the DEV VPS workflow (deployed 2026-09-02);
in-game/web playtest by the owner pending
Branch: feature/0.1b-earlygame-companions (work done alongside the 0.1b slice)

## Goal

Close the security gaps that stand between "server reachable by friends" and
"server reachable from the internet":

1. Privileged chat commands must not be reachable by normal players.
2. GM rank must be a closed, enforced range everywhere (Java and DB).
3. The publicly documented upstream seed account must not grant GM 6 on any
   environment we deploy.
4. Account creation for invited players must work without handing anyone the
   game DB credential — and without putting an HTTP server inside the game JVM.
5. The German handbook stays internal until the owner publishes it.

Security contract in `docs/DECISIONS.md` D13–D16; environment layout in
`docs/DEPLOYMENT.md`.

## Cosmic touchpoints

| File | Change | Why unavoidable |
| --- | --- | --- |
| `src/main/java/client/command/CommandsExecutor.java` | `gachalist`, `loot`, `mobskill` registered with explicit rank `2` instead of the defaulting overload (`addCommand(syntax, class)` = rank `0`); package-private `getCommandRank(String)` added for tests | The 2-arg overloads silently registered rank 0 (every player). No non-test production code was added. |
| `src/main/java/coop/security/GmLevel.java` (new) | single source of truth for the supported range `0..6` (`normalize`, `isValid`) | Clamping logic was duplicated and inconsistent |
| `src/main/java/client/Character.java` | `setGMLevel` now clamps once via `GmLevel.normalize` (the old code assigned `Math.min(level, 6)` and then overwrote it with `Math.max(level, 0)`, i.e. only the lower clamp survived: `setGMLevel(99)` stored `99`); `setGM` delegates to `setGMLevel` instead of assigning raw | These are the two entry points that persist/propagate GM level |
| `src/main/java/client/Client.java` | `setGMLevel` clamps via `GmLevel.normalize` | Login-time GM level comes from here; an out-of-range value would bypass command gating |
| `src/main/java/client/command/commands/gm6/SetGmLevelCommand.java` | rejects non-numeric input and values outside `0..6`; persists the rank under target synchronization before changing character/client state | The command parses player input directly and demotions must survive a crash |
| `src/main/java/coop/security/GmLevelPersistence.java` (new) | performs the narrow, parameterized `characters.gm` update and reports persistence failure | Avoids a full unrelated character save for one administrative field |

## Migrations (`db/extensions/coop-1230-security-hardening.xml`)

### `coop-1230` — neutralize the upstream seed credential

Runs **after** upstream data seeding (`changelog-root.xml` includes `extensions`
last), so it always sees the seeded account on a fresh database.

Behaviour by case:

| Case | Effect |
| --- | --- |
| Fresh database (seed `admin` / `Admin` with the documented bcrypt hash present) | Every character of an account using that password is set to `gm = 0`; the account's password is replaced, `banned = 1`, `loggedin = 0`, `banreason` set. Account rows are **not deleted**, so character/inventory data stays intact. |
| Password already rotated manually | `coop-1230` leaves the rotated credential intact unless the account still has the reserved name; `coop-1233` always disables every case variant of `admin` and demotes its characters. |
| Seed account renamed (name changed, password kept) | Still neutralized: matching is by password, not by name. |
| Seed account deleted | `UPDATE`s affect 0 rows → no-op. |
| Any database, including all of the above | A **disabled placeholder account** named `admin` is inserted if no account with that name exists (`banned = 1`, `loggedin = 0`, unusable password, `tos = 1`). This reserves the name so nobody can later register or auto-create `admin`. |

Consequences to keep in mind: the reserved `admin` row is banned forever, so a
desired admin account needs a different name. `ops/backup-dev-db.sh` /
`ops/restore-dev-db.sh` dump and restore the **whole** database including
`DATABASECHANGELOG`, so restoring a dump that predates `coop-1230` replays the
changeset on the next boot and neutralizes the seed again — nothing to do. The
one unsafe case is importing account rows (or a partial dump) into a database
whose changelog already contains `coop-1230`: the changeset will not re-run and
the seed hash stays active. In that case wipe and re-migrate
(`ops/reset-dev-db.sh`) and verify with `ops/smoke-test.sh`.

### `coop-1231`–`coop-1233` — enforce GM range and the reserved account name

`coop-1231` clamps existing rows (`gm < 0 → 0`, `gm > 6 → 6`). The separate,
non-transactional `coop-1232` adds `CHECK (gm BETWEEN 0 AND 6)` on `characters`
and marks itself ran if that named constraint already exists after a partial DDL run. Any code
path or SQL script that writes an out-of-range GM level fails loudly instead of
silently granting GM 6. It also means `ops/set-dev-gm.sh` /
`ops/set-vps-dev-gm.sh` (which take `0..6`) can never write an invalid value.
`coop-1233` then verifies the exact enforced check expression and neutralizes any
existing case variant of the reserved `admin` account name, including manually
rotated credentials.

## Registration app

Code: `src/main/java/coop/registration/`; assets:
`src/main/resources/coop/registration/public/`; image: `Dockerfile.registration`;
proxy template: `ops/Caddyfile.vps`; env template:
`ops/registration.env.example`.

### Guarantees

- **Separate process, separate container.** The game JVM opens no HTTP port. The
  registration container holds no WZ data, no game scripts or game config and
  adds no operational packages.
- **No game DB credential.** MySQL is reached only over the internal compose
  network as a least-privilege user (`INSERT` into `accounts`). The connection
  pool has 2 connections, 5 s connect timeout.
- **Secrets from files.** `REG_DB_PASSWORD_FILE` and `REG_INVITE_FILE` are read
  at startup; empty or unreadable files abort startup. Nothing is passed as an
  argument, nothing is in the image.
- **Transport/headers.** Served only over HTTPS through Caddy; the app itself
  sets CSP `default-src 'none'; style-src 'self'; img-src 'self'; form-action
  'self'; frame-ancestors 'none'; base-uri 'none'`, `X-Content-Type-Options:
  nosniff`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store`. Caddy
  replaces client-supplied `X-Forwarded-For`/`X-Real-IP`; the app trusts the
  replacement only from exact peers in `REG_TRUSTED_PROXY_IPS`. Caddy and the
  bounded app-side reader both cap request bodies at 16 KB.
- **Origin/Host check.** Requests are denied with 403 unless `Host` equals the
  host of `REG_PUBLIC_ORIGIN` and any `Origin` header equals it exactly.
- **CSRF + single-use session.** `GET /register` issues a random session cookie
  (`HttpOnly; Secure; SameSite=Strict; Max-Age=600`, path `/register`) and a
  per-session CSRF token. `POST` consumes the session (one attempt per loaded
  form), compares the token in constant time and rejects mismatch with 403.
  Retry pages issue a fresh cookie/token pair. Sessions expire after 10 minutes,
  and at most 256 pending sessions total or 4 per client are retained.
- **Rate limiting.** Per client IP: `REG_PER_IP_BURST` attempts per 15 min
  window (default 2); global: `REG_GLOBAL_HOURLY_CAP` per one-hour window (default 20).
  Exceeded → 429 (reload the form).
- **Invite passphrase.** Compared as a salted SHA-256 in constant time; not
  stored, not logged. Rotating the file rotates access.
- **Input validation.** Username `[A-Za-z0-9_]{4,13}`, password 12–64 printable
  ASCII characters and ≤ 72 bytes (bcrypt limit), confirmation compared in
  constant time.
- **Password storage.** `tools.BCrypt` with cost 12 — the same library and cost
  the login path verifies against, so these accounts work in the v83 client
  without changes.
- **No personal data.** No e-mail, no birthday, no analytics, no third-party
  requests (CSP `default-src 'none'`). `tos` is not set by the insert, matching
  the column default.

### Residual risk

1. **`AUTOMATIC_REGISTER: true` (D15).** The login server still creates accounts
   for any username on first login, so the invite passphrase protects only the
   web path. Everyone who can reach the login port can create an account, and
   such accounts get the configured default character slots. Flipping the flag
   is a separate owner-approved change with its own playtest.
2. **No mail/identity binding.** Anyone with the passphrase can create accounts;
   abuse is limited only by the rate limiter and by banning in the DB.
3. **`accounts.banned` is not set by registration**, so a newly created account
   can log in immediately — intended, but it means access revocation is manual.
4. **Rate-limit state is in memory.** A restart resets the buckets; the limits
   are abuse dampers, not hard quotas.
5. **Reverse-proxy configuration.** Forwarded addresses are ignored unless the
   immediate peer is explicitly listed in `REG_TRUSTED_PROXY_IPS`. The DEV
   integration therefore assigns Caddy the fixed internal IP `172.30.250.2`
   (D17); only that exact address is trusted.
6. **DDoS/TLS termination** is Caddy's job; there is no additional WAF or
   fail2ban configuration yet.
7. **Least-privilege DB user is provisioned by ops (D14)**, not by a migration.
   Until provisioning runs, `/health/ready` answers 503 and registration fails
   closed — good, but it must be verified per environment.

## Public handbook

Per D16 the PDF is not part of the registration image. The former
`/handbook.pdf` link was removed from `index.html`, so the landing page has no
dead link. Publishing a reviewed PDF remains a separate owner decision.

The handbook itself stays German and internal (`docs/handbook/`, built by
`ops/build-handbook.sh`); nothing in it is generated into the public site.

## Tests

Automated (`src/test/java`):

- `coop/security/GmLevelTest.java` — clamping over the full int range
- `client/command/CommandsExecutorSecurityTest.java` — command ranks, incl. the
  three re-ranked commands
- `coop/registration/RegistrationValidatorTest.java`,
  `RegistrationHandlerTest.java`, `RegistrationRateLimiterTest.java`,
  `RegistrationServerSecurityTest.java`, `BcryptCompatibilityTest.java`,
  `FormCodecTest.java`

`ops/smoke-test.sh` now also asserts: 0 accounts with the seed password, 0 GM
characters on neutralized seed accounts, one safe reserved `admin` tombstone,
`chk_characters_gm_level` present, 0 rows with `gm` outside `0..6`, and
`coop-1230` through `coop-1233` in `DATABASECHANGELOG`.

## Manual test checklist

Automated prerequisites (run first, both must be green):

```bash
ops/build.sh      # compile + tests + fat jar
ops/smoke-test.sh # boots the game server, checks migrations and port 8484
```

### Command ranks (needs a GM character, see `ops/set-dev-gm.sh`)

1. Create/keep a level-1 non-GM character; log in.
2. Type `@gachalist`, `@loot`, `@mobskill`.
   - Expected: "not a GM" / unknown-command style rejection, nothing happens.
   - Previously: the commands executed for every player.
3. Log in with a GM ≥ 2 character and repeat.
   - Expected: all three run.

### GM clamp

1. As GM 6: `@setgmlevel <other-online-character> 99` → message "GM level must
   be from 0 to 6.", no change.
2. `@setgmlevel <other> -1`, `@setgmlevel <other> abc` → same rejection.
3. `@setgmlevel <other> 3` → target and the executing GM both see level 3; the
   target's client shows the rank-3 command set after `@commands`.
4. Log out and back in → persisted level is 3 (not 0, not 6).
5. `SELECT gm FROM characters WHERE name = '<other>';` → `3`.

### Seed credential (`coop-1230`–`coop-1233`)

1. Fresh DB (`ops/reset-dev-db.sh`, then `ops/start.sh`) then
   `ops/smoke-test.sh` → seed credentials/GM rows are 0, one safe admin tombstone
   exists, and the exact enforced GM constraint is present with no invalid rows.
2. Try logging in as `admin` with the upstream default password → login fails.
3. `SELECT name, banned, banreason FROM accounts WHERE name = 'admin';` →
   `banned = 1`, banreason mentions `coop-1230`.
4. `SELECT c.name, c.gm FROM characters c JOIN accounts a ON a.id = c.accountid
   WHERE a.name = 'admin';` → `gm = 0`.
5. `UPDATE characters SET gm = 9 WHERE id = 1;` → must be rejected by
   `chk_characters_gm_level`.
6. Restore a full old backup (`ops/restore-dev-db.sh`) → re-run
   `ops/smoke-test.sh`; a pre-`coop-1230` changelog replays the migration. For a
   partial account import into a DB already recording these changesets, use a
   fresh migration instead of trusting the old changelog state.

### Registration app (VPS dev integration, behind Caddy — deployed)

Deployed via `ops/bootstrap-vps.sh` → `ops/provision-vps-registration.sh` →
`ops/deploy-dev.sh` → `ops/verify-vps.sh` (owner-approved, see `docs/DEPLOYMENT.md`
and D17). Fixed internal networks and exact-host DB grant as specified there.

1. `curl -si https://dream-ms.duckdns.org/health/ready` → `200 ready`
   (503 while the DB is unreachable or the user is not provisioned).
2. `curl -si http://dream-ms.duckdns.org/register` → 308 to HTTPS.
3. Browser: open `https://dream-ms.duckdns.org/register`, submit with a wrong
   invite code → error message, no account created.
4. Submit with the correct invite, `username=TestUser`, a 12+ character password
   and a mismatching confirmation → "Passwörter stimmen nicht überein."
5. Submit correctly → success page; `SELECT name, banned, loggedin FROM accounts
   WHERE name = 'TestUser';` → one row, password is a `$2y$12$` bcrypt hash.
6. Log into the v83 client with that account → PIN/PIC prompt appears, character
   creation works.
7. Reload `/register` and submit the same form twice → second attempt is 429.
8. Third attempt within the window from the same IP → 429 (per-IP limit).
9. `curl -si -X POST https://dream-ms.duckdns.org/register -H 'Origin:
   https://evil.example' …` → 403.
10. `curl -si -X POST … -H 'X-Forwarded-For: 1.2.3.4' …` repeated → still
    rate-limited on the real client IP (Caddy replaces the header).
11. Check response headers: CSP, `nosniff`, `no-referrer` present;
    `Strict-Transport-Security` absent (deliberate, D13/DEPLOYMENT).
12. `docker exec <registration-container> id` → `uid=10001(registration)`;
    `ps` shows no DB password in the command line; `docker history` shows no
    secret files in the image.
13. The landing page contains no handbook link until the owner separately
    approves publishing the reviewed PDF.

### VPS GM helper

```bash
# gameserver stopped, DB running
ops/set-vps-dev-gm.sh TestChar 4 --i-understand
```

- wrong argument count / level `7` / level `abc` / name `ab` / missing
  `--i-understand` → refusal, no DB change
- character logged in → refusal; banned account → refusal
- success → one line: `VPS dev character 'TestChar' (id …): GM level 0 -> 4
  (1 row changed).`
- `bash -x ops/set-vps-dev-gm.sh …` → refuses immediately

## Balance assumptions

None — this slice changes no gameplay numbers. The only tunable values are the
rate limits and the invite passphrase, both environment configuration.

## Playtest feedback

(filled by owner after testing)
