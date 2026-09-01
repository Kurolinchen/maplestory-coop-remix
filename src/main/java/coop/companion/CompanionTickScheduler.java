/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.companion;

import client.Character;
import coop.config.CoopDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;

import java.util.concurrent.ScheduledFuture;

/**
 * Slice C (Companion navigation): drives one {@link CompanionFollowController}
 * tick per active session at the configured cadence.
 *
 * <p>Deliberately minimal: no pathfinding, no autonomous routing, no RTS-style
 * behaviour. A tick does at most one of:
 * <ul>
 *   <li>reposition the companion behind the owner on the same map;</li>
 *   <li>follow the owner through one static, scriptless, allowlisted portal;</li>
 *   <li>dismiss + save the companion when the owner leaves an eligible map.</li>
 * </ul>
 *
 * <p>Tick failures are counted; after {@code MAX_CONSECUTIVE_FAILURES} the bot
 * stops acting but the session is retained so state is not lost.
 */
public final class CompanionTickScheduler {
    private static final Logger log = LoggerFactory.getLogger(CompanionTickScheduler.class);

    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private static final CompanionTickScheduler INSTANCE = new CompanionTickScheduler();

    public static CompanionTickScheduler getInstance() {
        return INSTANCE;
    }

    private final CompanionManager manager = CompanionManager.getInstance();
    private final CompanionFollowController follow = new CompanionFollowController();

    private volatile boolean running = false;
    private ScheduledFuture<?> task;

    private CompanionTickScheduler() {
    }

    /** Starts the shared tick loop. Idempotent; safe to call on every world start. */
    public synchronized void start() {
        if (running) {
            return;
        }
        long period = CoopDefaults.companionTickMs();
        // TimerManager.register(Runnable, delay, repeat) is millisecond-based
        // and returns a ScheduledFuture we can cancel on shutdown.
        task = (ScheduledFuture<?>) TimerManager.getInstance()
                .register(() -> runTickSafely(), period, period);
        running = true;
        log.info("Companion tick scheduler started (period {}ms)", period);
    }

    public synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        running = false;
        log.info("Companion tick scheduler stopped");
    }

    public boolean isRunning() {
        return running;
    }

    private void runTickSafely() {
        if (manager.isShuttingDown()) {
            return;
        }
        for (CompanionSession session : manager.activeSessions()) {
            if (session.state() != CompanionSession.State.ACTIVE) {
                continue;
            }
            try {
                tickSession(session);
            } catch (RuntimeException e) {
                session.recordTickFailure(e.getMessage());
                log.warn("Companion tick failed owner={} companion={}: {}",
                        session.ownerCharacterId(), session.companionCharacterId(), e.getMessage());
            }
        }
    }

    private void tickSession(CompanionSession session) {
        Character owner = resolveOwner(session);
        if (owner == null) {
            session.recordTickFailure("owner not resolvable");
            return;
        }
        Character bot = owner.getMap() != null
                ? owner.getMap().getCharacterById(session.companionCharacterId()) : null;
        if (bot == null) {
            session.recordTickFailure("companion not on owner's map");
            return;
        }
        CompanionFollowController.OwnerTracker tracker = trackers
                .computeIfAbsent(session.ownerCharacterId(),
                        k -> new CompanionFollowController.OwnerTracker());
        if (session.consecutiveFailures() >= MAX_CONSECUTIVE_FAILURES) {
            // Frozen: stop acting until an operator intervenes, but never
            // discard the loaded character.
            return;
        }
        CompanionFollowController.TickResult result = follow.tick(session, owner, bot, tracker);
        if (result.dismissed()) {
            log.info("Companion follow dismissing owner={} companion={}: {}",
                    session.ownerCharacterId(), session.companionCharacterId(), result.reason());
            manager.release(session);
            trackers.remove(session.ownerCharacterId());
            CompanionLifecycleService.getInstance().dismiss(session, bot);
            return;
        }
        session.markTickCompleted();
    }

    private Character resolveOwner(CompanionSession session) {
        try {
            net.server.world.World world = net.server.Server.getInstance()
                    .getWorld(session.world());
            if (world == null) {
                return null;
            }
            return world.getPlayerStorage().getCharacterById(session.ownerCharacterId());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private final java.util.Map<Integer, CompanionFollowController.OwnerTracker> trackers =
            new java.util.concurrent.ConcurrentHashMap<>();
}
