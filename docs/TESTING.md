# Testing

## Automated tests

Framework: JUnit 5 + Mockito (pom versions), run via Maven Wrapper:

```
ops/test.sh                  # tests only
ops/build.sh                 # full build incl. tests + fat jar (target/Cosmic.jar)
sh ./mvnw -B test -Dtest=ClassName   # single class (never bare `mvn`, see DECISIONS D4/D6)
```

Baseline (2026-08-30, commit fec53bc77): **1886 tests, 0 failures** — including
`ScriptEvaluationTest` (1821 cases evaluating every JS script) which catches broken
NPC/quest/event/portal scripts at build time.

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

## Manual playtest convention (human in the loop)

Agents cannot play the game. Every gameplay feature PR/spec must end with a
**Manual test steps** section (`docs/features/<feature>.md`):

1. exact GM setup commands (see `client/command/commands/gm*`)
2. steps to perform in the client
3. expected observable result
4. what data/log to check afterwards (e.g. `characterexplogs`, `bosslog_daily`)

The owner performs these steps and reports feedback; agents fix from that feedback.

## Regression triage

When something breaks, classify before fixing (required in reports):

- **upstream issue** — reproduce on untouched `upstream/master` → report, patch only if blocking
- **environment issue** — Docker/Java/config/permissions → fix in ops/env, not game code
- **our change** — bisect within our commits, fix + regression test
