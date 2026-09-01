/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.companion;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Slice B (Companion Bot MVP): immutable snapshot of a companion session's
 * identity + the mutable runtime state we expose to commands and diagnostics.
 *
 * <p>State machine (Slice B only covers spawn + dismiss; combat / follow are
 * added in Slice C/D):
 * <pre>
 *   NEW -> ACTIVE -> DISMISSING -> CLOSED
 *                 \-> SAVE_FAILED  (held until shutdown / GM)
 * </pre>
 *
 * <p>Concurrency: every state transition acquires the companion's
 * {@link java.util.concurrent.locks.Lock} inside the
 * {@code CompanionLifecycleService}; callers should never mutate state directly.
 */
public final class CompanionSession {
    public enum Mode {
        PASSIVE, FOLLOW, GRIND, SUPPORT, STAY;

        public static Mode parse(String raw, Mode fallback) {
            if (raw == null) return fallback;
            try {
                return Mode.valueOf(raw.toUpperCase());
            } catch (IllegalArgumentException ex) {
                return fallback;
            }
        }
    }

    public enum State {
        NEW, ACTIVE, DISMISSING, SAVE_FAILED, CLOSED
    }

    private final int ownerCharacterId;
    private final int companionCharacterId;
    private final int accountId;
    private final int world;
    private final int channel;
    private final Mode mode;
    private final boolean lootEnabled;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private volatile long lastTickAt;
    private volatile long lastSaveAt;
    private volatile int consecutiveFailures;
    private volatile String lastError;
    private final java.util.concurrent.locks.Lock lock = new java.util.concurrent.locks.ReentrantLock(true);

    public CompanionSession(int ownerCharacterId, int companionCharacterId,
                           int accountId, int world, int channel,
                           Mode mode, boolean lootEnabled) {
        this.ownerCharacterId = ownerCharacterId;
        this.companionCharacterId = companionCharacterId;
        this.accountId = accountId;
        this.world = world;
        this.channel = channel;
        this.mode = mode;
        this.lootEnabled = lootEnabled;
    }

    public int ownerCharacterId() { return ownerCharacterId; }
    public int companionCharacterId() { return companionCharacterId; }
    public int accountId() { return accountId; }
    public int world() { return world; }
    public int channel() { return channel; }
    public Mode mode() { return mode; }
    public boolean lootEnabled() { return lootEnabled; }
    public State state() { return state.get(); }
    public long lastTickAt() { return lastTickAt; }
    public long lastSaveAt() { return lastSaveAt; }
    public int consecutiveFailures() { return consecutiveFailures; }
    public String lastError() { return lastError; }
    public java.util.concurrent.locks.Lock lock() { return lock; }

    public boolean compareAndSetState(State expected, State next) {
        return state.compareAndSet(expected, next);
    }

    public void markTickCompleted() {
        lastTickAt = System.currentTimeMillis();
        consecutiveFailures = 0;
    }

    public void recordTickFailure(String reason) {
        consecutiveFailures++;
        lastError = reason;
    }

    public void markSaveCompleted() {
        lastSaveAt = System.currentTimeMillis();
    }

    /** Snapshot for the @companion status command. */
    public String snapshot() {
        return "owner=" + ownerCharacterId
                + " companion=" + companionCharacterId
                + " world=" + world + " channel=" + channel
                + " mode=" + mode
                + " state=" + state.get()
                + " loot=" + lootEnabled
                + " failures=" + consecutiveFailures
                + (lastError == null ? "" : " lastError=" + lastError);
    }
}
