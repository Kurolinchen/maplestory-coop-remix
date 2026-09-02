# Testing

## Automated tests

Framework: JUnit 5 + Mockito (pom versions), run via Maven Wrapper:

```
ops/test.sh                  # tests only
ops/build.sh                 # full build incl. tests + fat jar (target/Cosmic.jar)
sh ./mvnw -B test -Dtest=ClassName   # single class (never bare `mvn`, see DECISIONS D4/D6)
```

Upstream baseline (2026-08-30, commit fec53bc77): **1886 tests, 0 failures** — including
`ScriptEvaluationTest` (1821 cases evaluating every JS script) which catches broken
NPC/quest/event/portal scripts at build time.

Milestone 0.1 baseline (2026-08-31): **1911 tests, 0 failures** (1886 upstream + 25
Co-op Remix tests; see `docs/features/0.1-coop-qol.md`).

### Handler test pattern (recommended for gameplay logic)

Follow `src/test/java/testutil/HandlerTest.java`:

```java
@ExtendWith(MockitoExtension.class)
class MyHandlerTest extends HandlerTest {          // provides mocked Client + Character
    private final MyHandler handler = new MyHandler();
    @Mock private SomeDependency dependency;

    @BeforeEach
    void setUp() {
        lenient().when(chr.getSomeDependency()).thenReturn(dependency);
    }

    @Test
    void doesTheThing() {
        InPacket packet = Packets.buildInPacket(out -> {
            out.writeByte(1);                        // mirror the wire format exactly
        });
        handler.handlePacket(packet, client);
        verify(dependency).expectedCall();
    }
}
```

Existing example: `net/server/channel/handlers/CashShopSurpriseHandlerTest.java`.

### Rules for new tests

- Every gameplay feature ships with tests where practical (at minimum for new handlers,
  processors and services; pure packet-shaping may be covered by Packet tests).
- Bug fixes ship with a regression test reproducing the bug first.
- Balance changes are data/config changes — verify with queries/analysis instead of unit
  tests where appropriate (see `/balance`).

## Smoke test (boots the actual server)

```
ops/smoke-test.sh
```

Starts DB + server, waits ≤180 s for the log line `Cosmic is now online`
(`net/server/Server.java:942`), verifies login port 8484 listens, then stops everything.
Run it after changes that touch startup, DB migrations, config loading, WZ loading, scripts.

## Registration web app (separate process)

`src/main/java/coop/registration` has no Netty/handler dependency and is covered by plain
JUnit tests (`src/test/java/coop/registration/`): `RegistrationValidatorTest`,
`RegistrationHandlerTest` (CSRF, session, origin/host checks, invite),
`RegistrationRateLimiterTest`, `RegistrationServerSecurityTest` (bounded body and trusted proxy),
`BcryptCompatibilityTest` (hashes must verify with the game server's `tools.BCrypt`) and
`FormCodecTest`. They run with everything else:

```
ops/test.sh                                   # or: sh ./mvnw -B test -Dtest=RegistrationHandlerTest
```

Manual checks against a running container (no DB root credential needed). The port is
published for this local check only — behind Caddy nothing publishes it (see
`docs/DEPLOYMENT.md`). Every request except `/health/ready` is denied with 403 unless the
`Host` header matches `REG_PUBLIC_ORIGIN`, so pass it explicitly:

```bash
docker build -f Dockerfile.registration -t maple-registration .
# env comes from a gitignored copy of ops/registration.env.example,
# secret files are mounted read-only and referenced by path
docker run --rm --network <internal-net> -p 8080:8080 \
  --env-file /path/to/registration.env \
  -v /path/to/secrets:/run/secrets:ro maple-registration

HOST_HDR='Host: dream-ms.duckdns.org'
curl -si http://127.0.0.1:8080/health/ready              # 200 "ready" while the DB is reachable, 503 otherwise
curl -si -H "$HOST_HDR" -c /tmp/cookies http://127.0.0.1:8080/register
                                                         # 200 + HTML with a fresh CSRF token + session cookie
curl -si -H "$HOST_HDR" -b /tmp/cookies -X POST http://127.0.0.1:8080/register \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data 'csrf=<token from the GET>&invite=…&username=TestUser&password=…&confirmation=…'
```

Negative cases to verify by hand: wrong `Host`/`Origin` → `403`; missing/stale CSRF or session
cookie → `429`; body over 16 KiB → `413`; wrong invite passphrase → reusable form with a fresh
cookie/token pair; duplicate username → form with error; rate limit exceeded → `429`. Full checklist:
`docs/features/security-registration-and-public-handbook.md`.

`ops/build.sh` (full build incl. tests + fat jar) and `ops/smoke-test.sh` stay **mandatory**
for every change that touches the game server, migrations or config — the registration tests
are an addition, never a replacement. `ops/smoke-test.sh` additionally asserts the security
migration state: no active seeded credentials, no seeded GM characters, the
reserved admin tombstone is safe, the exact enforced `chk_characters_gm_level`
constraint exists and no `characters.gm` row is outside `0..6`.

## Local client verification

The proprietary client stays below `.local/` and is never committed. The launcher performs
a mandatory full SHA-256 verification of the runtime copy before every start:

```bash
python3 tools/client/patch-client-ip.py --self-test
tools/client/build-client-profile.sh local-dev
ops/client-status.sh
ops/client-run-local.sh
```

`ops/client-status.sh` returns non-zero if the true-win32 runtime, manifest hashes or login
port are unavailable. Hashing all client files can take several seconds. Source provenance,
runner setup and troubleshooting are documented in `docs/CLIENT.md` and `docs/client/`.

## Manual playtest convention (human in the loop)

Agents cannot play the game. Every gameplay feature PR/spec must end with a
**Manual test steps** section (`docs/features/<feature>.md`):

1. exact GM setup commands (see `client/command/commands/gm*`)
2. steps to perform in the client
3. expected observable result
4. what data/log to check afterwards (e.g. `characterexplogs`, `bosslog_daily`)

The owner performs these steps and reports feedback; agents fix from that feedback.

### Milestone 0.1 GM setup

`ops/set-dev-gm.sh` updates only an offline character in the guarded local Compose project.
The gameserver must be fully stopped so no final in-memory character save can overwrite the
change; keep the DB service running:

```bash
ops/stop-server.sh
ops/set-dev-gm.sh <character-name>
ops/start.sh
```

The helper always sets GM level 4, verifies the Compose project/service/volume and never
prints database credentials. Log in again after it succeeds. Then follow the full checklist
in `docs/features/0.1-coop-qol.md`.

On the VPS DEV instance the same promotion is done with
`ops/set-vps-dev-gm.sh <character-name> <0..6> --i-understand` (gameserver stopped, DB
running, account offline and unbanned).

For a fresh non-GM playtest while retaining tester accounts and slot capacity:

```bash
ops/backup-dev-db.sh
ops/wipe-characters.sh --i-understand
```

The wipe is destructive and confirms interactively. It preserves accounts,
character-slot capacity, Liquibase state, storage and account-owned inventory.

## Regression triage

When something breaks, classify before fixing (required in reports):

- **upstream issue** — reproduce on untouched `upstream/master` → report, patch only if blocking
- **environment issue** — Docker/Java/config/permissions → fix in ops/env, not game code
- **our change** — bisect within our commits, fix + regression test
