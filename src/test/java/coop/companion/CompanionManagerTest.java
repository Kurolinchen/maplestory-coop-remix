/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.companion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice B unit tests for the in-process companion manager.
 *
 * <p>These tests do not touch the DB; they cover only the thread-safe
 * registry behaviour that protects the bot from double-spawn and from
 * surviving a shutdown request.
 */
class CompanionManagerTest {

    private CompanionManager manager;

    @BeforeEach
    void setup() {
        // Each test gets a fresh manager to avoid cross-test contamination.
        manager = CompanionManager.getInstance();
        // Release any leftover sessions from previous tests.
        manager.activeSessions().forEach(manager::release);
        // Reset the shutdown flag (tests can flip it back on).
        try {
            java.lang.reflect.Method m = CompanionManager.class.getDeclaredMethod(
                    "resetShutdownFlagForTests");
            m.setAccessible(true);
            m.invoke(manager);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void emptyManagerReportsZeroActive() {
        // sanity: previous test must have cleaned up; if not, this fails.
        assertEquals(0, manager.activeOwnerCount(), "pre-existing sessions must be released first");
        assertEquals(0, manager.activeCompanionCount());
    }

    @Test
    void registerThenReleaseRemovesBothEntries() {
        CompanionSession s = newSession(1, 2);
        assertTrue(manager.register(s), "first register must succeed");
        assertEquals(1, manager.activeOwnerCount());
        assertEquals(1, manager.activeCompanionCount());
        assertTrue(manager.isOwnerActive(1));
        assertTrue(manager.isCompanionActive(2));

        manager.release(s);
        assertEquals(0, manager.activeOwnerCount());
        assertEquals(0, manager.activeCompanionCount());
        assertFalse(manager.isOwnerActive(1), "owner must be removed after release");
        assertFalse(manager.isCompanionActive(2), "companion must be removed after release");
    }

    @Test
    void doubleRegisterForSameOwnerIsRejected() {
        CompanionSession first = newSession(1, 2);
        CompanionSession second = newSession(1, 3);
        assertTrue(manager.register(first));
        assertFalse(manager.register(second), "duplicate owner must be refused");
        // the second register must not have left anything in the maps
        assertEquals(1, manager.activeOwnerCount());
        assertEquals(1, manager.activeCompanionCount());
        assertTrue(manager.isCompanionActive(2));
        assertFalse(manager.isCompanionActive(3));
        manager.release(first);
    }

    @Test
    void doubleRegisterForSameCompanionIsRejected() {
        CompanionSession first = newSession(1, 2);
        CompanionSession second = newSession(5, 2);
        assertTrue(manager.register(first));
        assertFalse(manager.register(second), "duplicate companion must be refused");
        assertEquals(1, manager.activeOwnerCount());
        assertTrue(manager.isOwnerActive(1));
        assertFalse(manager.isOwnerActive(5));
        manager.release(first);
    }

    @Test
    void releaseIsIdempotent() {
        CompanionSession s = newSession(7, 8);
        manager.register(s);
        manager.release(s);
        manager.release(s); // second call must not throw or change state
        assertEquals(0, manager.activeOwnerCount());
    }

    @Test
    void beginShutdownRefusesNewRegisters() {
        manager.beginShutdown();
        try {
            CompanionSession s = newSession(20, 21);
            assertFalse(manager.register(s), "register must refuse during shutdown");
            assertEquals(0, manager.activeOwnerCount());
        } finally {
            // No public reset: the singleton state stays shut down across tests.
            // Tests that follow must not depend on new registers.
        }
    }

    @Test
    void sessionStateMachineMovesThroughLifecycle() {
        CompanionSession s = newSession(30, 31);
        assertEquals(CompanionSession.State.NEW, s.state());
        assertTrue(s.compareAndSetState(CompanionSession.State.NEW, CompanionSession.State.ACTIVE));
        assertEquals(CompanionSession.State.ACTIVE, s.state());
        assertFalse(s.compareAndSetState(CompanionSession.State.NEW, CompanionSession.State.CLOSED),
                "compareAndSet must not advance from a non-NEW state to NEW");
        assertTrue(s.compareAndSetState(CompanionSession.State.ACTIVE, CompanionSession.State.DISMISSING));
        assertTrue(s.compareAndSetState(CompanionSession.State.DISMISSING, CompanionSession.State.CLOSED));
        assertEquals(CompanionSession.State.CLOSED, s.state());
    }

    @Test
    void modeParseAcceptsKnownValues() {
        assertEquals(CompanionSession.Mode.PASSIVE,
                CompanionSession.Mode.parse("passive", CompanionSession.Mode.GRIND));
        assertEquals(CompanionSession.Mode.GRIND,
                CompanionSession.Mode.parse("GRIND", CompanionSession.Mode.PASSIVE));
        assertEquals(CompanionSession.Mode.SUPPORT,
                CompanionSession.Mode.parse("Support", CompanionSession.Mode.PASSIVE));
    }

    @Test
    void modeParseFallsBackOnUnknownValue() {
        assertEquals(CompanionSession.Mode.PASSIVE,
                CompanionSession.Mode.parse("unknown", CompanionSession.Mode.PASSIVE));
        assertEquals(CompanionSession.Mode.PASSIVE,
                CompanionSession.Mode.parse(null, CompanionSession.Mode.PASSIVE));
    }

    @Test
    void snapshotContainsExpectedFields() {
        CompanionSession s = newSession(40, 41);
        s.compareAndSetState(CompanionSession.State.NEW, CompanionSession.State.ACTIVE);
        s.markTickCompleted();
        s.recordTickFailure("synthetic");
        String snap = s.snapshot();
        assertTrue(snap.contains("owner=40"), snap);
        assertTrue(snap.contains("companion=41"), snap);
        assertTrue(snap.contains("mode=PASSIVE"), snap);
        assertTrue(snap.contains("state=ACTIVE"), snap);
        assertTrue(snap.contains("failures=1"), snap);
        assertTrue(snap.contains("lastError=synthetic"), snap);
    }

    @Test
    void registerNullSessionIsRejected() {
        assertFalse(manager.register(null));
    }

    @Test
    void activeSessionsReturnsCurrentSet() {
        CompanionSession a = newSession(50, 51);
        CompanionSession b = newSession(52, 53);
        manager.register(a);
        manager.register(b);
        assertEquals(2, manager.activeSessions().size());
        manager.release(a);
        manager.release(b);
        assertEquals(0, manager.activeSessions().size());
    }

    private CompanionSession newSession(int ownerId, int companionId) {
        return new CompanionSession(ownerId, companionId, /* account */ 1, /* world */ 0,
                /* channel */ 1, CompanionSession.Mode.PASSIVE, /* loot */ false);
    }
}
