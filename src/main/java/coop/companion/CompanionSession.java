/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.companion;

import client.Character;

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
    private volatile Character companion;
    private volatile boolean discardOnRecovery;
    private final java.util.concurrent.locks.Lock lock = new java.util.concurrent.locks.ReentrantLock(true);

    // Per-session action cooldowns. These MUST live here and not on the shared
    // controller instances: a controller field would be shared by every active
    // companion, capping total server-wide companion DPS instead of each bot's.
    private volatile long lastAttackAt;
    private volatile long lastIncomingAt;
    private volatile long lastLootAt;
    private volatile long lastConsumeAt;
    private volatile long lastSaveAttemptAt;

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
    public Character companion() { return companion; }
    public boolean discardOnRecovery() { return discardOnRecovery; }

    void setCompanion(Character companion) {
        this.companion = companion;
    }

    void markFailedSpawnRecovery() {
        discardOnRecovery = true;
    }

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

    // ---- per-session cooldown helpers -------------------------------------

    /** True when at least {@code intervalMs} has passed since {@code lastAt}. */
    private static boolean elapsed(long lastAt, long intervalMs) {
        return System.currentTimeMillis() - lastAt >= intervalMs;
    }

    /** Marks an action as having just run and returns true if it was allowed. */
    private static boolean tryAcquire(java.util.function.LongSupplier getter,
                                      java.util.function.LongConsumer setter,
                                      long intervalMs) {
        long now = System.currentTimeMillis();
        if (now - getter.getAsLong() < intervalMs) {
            return false;
        }
        setter.accept(now);
        return true;
    }

    public boolean tryAttack(long intervalMs) {
        return tryAcquire(this::getLastAttackAt, this::setLastAttackAt, intervalMs);
    }

    public boolean tryIncomingDamage(long intervalMs) {
        return tryAcquire(this::getLastIncomingAt, this::setLastIncomingAt, intervalMs);
    }

    public boolean tryLoot(long intervalMs) {
        return tryAcquire(this::getLastLootAt, this::setLastLootAt, intervalMs);
    }

    public boolean tryConsume(long intervalMs) {
        return tryAcquire(this::getLastConsumeAt, this::setLastConsumeAt, intervalMs);
    }

    public boolean trySave(long intervalMs) {
        return tryAcquire(this::getLastSaveAttemptAt, this::setLastSaveAttemptAt, intervalMs);
    }

    public long getLastAttackAt() { return lastAttackAt; }
    public void setLastAttackAt(long v) { lastAttackAt = v; }
    public long getLastIncomingAt() { return lastIncomingAt; }
    public void setLastIncomingAt(long v) { lastIncomingAt = v; }
    public long getLastLootAt() { return lastLootAt; }
    public void setLastLootAt(long v) { lastLootAt = v; }
    public long getLastConsumeAt() { return lastConsumeAt; }
    public void setLastConsumeAt(long v) { lastConsumeAt = v; }
    public long getLastSaveAttemptAt() { return lastSaveAttemptAt; }
    public void setLastSaveAttemptAt(long v) { lastSaveAttemptAt = v; }

    /** True when the given action is still on cooldown. */
    public boolean onCooldown(long lastAt, long intervalMs) {
        return !elapsed(lastAt, intervalMs);
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
