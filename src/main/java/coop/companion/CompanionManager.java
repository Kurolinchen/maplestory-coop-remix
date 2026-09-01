/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.companion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slice B (Companion Bot MVP): singleton manager that owns the active
 * companion session map. Per-process state only; persistence is handled by
 * {@link CompanionBindingRepository}.
 *
 * <p>The manager never spawns or destroys characters by itself. Lifecycle
 * orchestration lives in {@code CompanionLifecycleService} (Slice B.2) and is
 * driven by commands and shutdown hooks; the manager just exposes the
 * thread-safe registry needed by those callers.
 */
public final class CompanionManager {
    private static final Logger log = LoggerFactory.getLogger(CompanionManager.class);

    private static final CompanionManager INSTANCE = new CompanionManager();

    public static CompanionManager getInstance() {
        return INSTANCE;
    }

    private final ConcurrentHashMap<Integer, CompanionSession> byOwner = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, CompanionSession> byCompanion = new ConcurrentHashMap<>();
    private volatile boolean shutdownInProgress = false;

    private CompanionManager() {
    }

    /** Returns the live session for the given owner character, if any. */
    public Optional<CompanionSession> findByOwner(int ownerCharacterId) {
        return Optional.ofNullable(byOwner.get(ownerCharacterId));
    }

    /** Returns the live session for the given companion character, if any. */
    public Optional<CompanionSession> findByCompanion(int companionCharacterId) {
        return Optional.ofNullable(byCompanion.get(companionCharacterId));
    }

    /**
     * Reserves the active slot for the owner + companion pair. Refuses if:
     * <ul>
     *   <li>the manager is shutting down;</li>
     *   <li>either character already has a live session;</li>
     * </ul>
     *
     * <p>The caller is responsible for closing the session via
     * {@link #release(CompanionSession)} when the companion is dismissed.
     */
    public boolean register(CompanionSession session) {
        if (shutdownInProgress) {
            return false;
        }
        if (session == null) {
            return false;
        }
        CompanionSession prior = byOwner.putIfAbsent(session.ownerCharacterId(), session);
        if (prior != null) {
            return false;
        }
        prior = byCompanion.putIfAbsent(session.companionCharacterId(), session);
        if (prior != null) {
            byOwner.remove(session.ownerCharacterId(), session);
            return false;
        }
        log.info("Companion registered: owner={} companion={} mode={}",
                session.ownerCharacterId(), session.companionCharacterId(), session.mode());
        return true;
    }

    /** Removes the session from the manager. Safe to call multiple times. */
    public void release(CompanionSession session) {
        if (session == null) {
            return;
        }
        byOwner.remove(session.ownerCharacterId(), session);
        byCompanion.remove(session.companionCharacterId(), session);
        log.info("Companion released: owner={} companion={}",
                session.ownerCharacterId(), session.companionCharacterId());
    }

    public boolean isCompanionActive(int characterId) {
        return byCompanion.containsKey(characterId);
    }

    public boolean isOwnerActive(int characterId) {
        return byOwner.containsKey(characterId);
    }

    public int activeOwnerCount() {
        return byOwner.size();
    }

    public int activeCompanionCount() {
        return byCompanion.size();
    }

    public Collection<CompanionSession> activeSessions() {
        return byOwner.values();
    }

    /** Marks the manager as refusing new spawns. Existing sessions must still be released explicitly. */
    public void beginShutdown() {
        shutdownInProgress = true;
        log.info("Companion manager entering shutdown ({} active sessions)", byOwner.size());
    }

    public boolean isShuttingDown() {
        return shutdownInProgress;
    }

    /**
     * Test-only hook: clears the shutdown flag. Production code never calls this;
     * it exists so {@code CompanionManagerTest} can re-use the singleton without
     * running tests in a fixed order.
     */
    void resetShutdownFlagForTests() {
        shutdownInProgress = false;
    }
}
