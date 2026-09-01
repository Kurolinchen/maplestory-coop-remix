/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.earlygame;

import coop.config.CoopDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/** Asynchronous, bounded EXP telemetry for levels 1-30. */
public final class EarlyGameTelemetry {
    private static final Logger log = LoggerFactory.getLogger(EarlyGameTelemetry.class);
    private static final String SOURCE = "UNATTRIBUTED";
    private static final int BATCH_SIZE = CoopDefaults.earlyGameTelemetryBatchSize();
    private static final LinkedBlockingQueue<ExpRecord> QUEUE =
            new LinkedBlockingQueue<>(CoopDefaults.earlyGameTelemetryQueueCapacity());
    private static final ReentrantLock FLUSH_LOCK = new ReentrantLock();
    private static final Object LIFECYCLE_LOCK = new Object();

    private static ScheduledExecutorService executor;
    private static boolean shuttingDown;

    public record ExpRecord(int characterId, int level, int jobId, int mapId,
                            long gainedExp, String source) {
    }

    private EarlyGameTelemetry() {
    }

    /** Queues accepted EXP without blocking gameplay. Invalid/out-of-range awards are ignored. */
    public static void recordAcceptedGain(int characterId, int preAwardLevel, int jobId,
                                          int mapId, long acceptedExp) {
        ExpRecord record = acceptedRecord(characterId, preAwardLevel, jobId, mapId, acceptedExp);
        if (record == null || !startIfNeeded()) {
            return;
        }
        QUEUE.offer(record);
    }

    static ExpRecord acceptedRecord(int characterId, int preAwardLevel, int jobId,
                                    int mapId, long acceptedExp) {
        if (acceptedExp <= 0 || preAwardLevel < 1 || preAwardLevel > 30) {
            return null;
        }
        return new ExpRecord(characterId, preAwardLevel, jobId, mapId, acceptedExp, SOURCE);
    }

    private static boolean startIfNeeded() {
        synchronized (LIFECYCLE_LOCK) {
            if (shuttingDown) {
                return false;
            }
            if (executor != null && !executor.isShutdown()) {
                return true;
            }

            int interval = CoopDefaults.earlyGameTelemetryFlushSeconds();
            executor = Executors.newSingleThreadScheduledExecutor(threadFactory(
                    "coop-early-game-telemetry"));
            executor.scheduleWithFixedDelay(
                    EarlyGameTelemetry::flushOneBatch, interval, interval, TimeUnit.SECONDS);
            log.info("Early-game telemetry started (flush every {}s, batch {}, queue {})",
                    interval, BATCH_SIZE, QUEUE.remainingCapacity() + QUEUE.size());
            return true;
        }
    }

    private static ThreadFactory threadFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        };
    }

    private static void flushOneBatch() {
        FLUSH_LOCK.lock();
        try {
            List<ExpRecord> drained = new ArrayList<>(BATCH_SIZE);
            QUEUE.drainTo(drained, BATCH_SIZE);
            if (drained.isEmpty()) {
                return;
            }

            try (Connection con = DatabaseConnection.getConnection()) {
                int dropped = insertBatchPreservingValid(con, drained);
                if (dropped > 0) {
                    log.warn("Early-game telemetry dropped {} records that no longer satisfy DB constraints",
                            dropped);
                }
            } catch (SQLException e) {
                log.warn("Early-game telemetry flush failed ({} records dropped): {}",
                        drained.size(), e.getMessage());
            } catch (Throwable t) {
                // Scheduled executors suppress future runs when a task escapes.
                log.warn("Early-game telemetry flush failed unexpectedly ({} records dropped): {}",
                        drained.size(), t.toString());
            }
        } finally {
            FLUSH_LOCK.unlock();
        }
    }

    /** Splits an integrity-failing batch so one deleted character cannot drop unrelated records. */
    static int insertBatchPreservingValid(Connection con, List<ExpRecord> records) throws SQLException {
        try {
            insertBatch(con, records);
            return 0;
        } catch (SQLException e) {
            if (!isIntegrityViolation(e)) {
                throw e;
            }
            if (records.size() == 1) {
                return 1;
            }
            int middle = records.size() / 2;
            return insertBatchPreservingValid(con, records.subList(0, middle))
                    + insertBatchPreservingValid(con, records.subList(middle, records.size()));
        }
    }

    private static boolean isIntegrityViolation(SQLException e) {
        for (SQLException current = e; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if (state != null && state.startsWith("23")) {
                return true;
            }
        }
        return false;
    }

    static void insertBatch(Connection con, List<ExpRecord> records) throws SQLException {
        con.setAutoCommit(false);
        try {
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO coop_early_game_exp_log "
                            + "(characterid, level, jobid, mapid, gained_exp, source) "
                            + "VALUES (?, ?, ?, ?, ?, ?)")) {
                for (ExpRecord record : records) {
                    ps.setInt(1, record.characterId());
                    ps.setInt(2, record.level());
                    ps.setInt(3, record.jobId());
                    ps.setInt(4, record.mapId());
                    ps.setLong(5, record.gainedExp());
                    ps.setString(6, record.source());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            con.commit();
        } catch (SQLException | RuntimeException e) {
            try {
                con.rollback();
            } catch (SQLException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            throw e;
        }
    }

    /** Stops periodic work and waits only the configured time for a final serialized drain. */
    public static void shutdown() {
        ScheduledExecutorService current;
        synchronized (LIFECYCLE_LOCK) {
            if (shuttingDown) {
                return;
            }
            shuttingDown = true;
            current = executor;
            executor = null;
        }

        if (current != null) {
            current.shutdown();
        }

        int timeoutSeconds = CoopDefaults.earlyGameTelemetryShutdownSeconds();
        ExecutorService finalizer = Executors.newSingleThreadExecutor(
                threadFactory("coop-early-game-telemetry-shutdown"));
        Future<?> finalFlush = finalizer.submit(() -> {
            while (!QUEUE.isEmpty() && !Thread.currentThread().isInterrupted()) {
                flushOneBatch();
            }
        });
        finalizer.shutdown();
        try {
            finalFlush.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            finalFlush.cancel(true);
            log.warn("Early-game telemetry shutdown flush timed out with {} records queued",
                    QUEUE.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finalFlush.cancel(true);
        } catch (Exception e) {
            log.warn("Early-game telemetry shutdown flush failed: {}", e.toString());
        } finally {
            finalizer.shutdownNow();
            synchronized (LIFECYCLE_LOCK) {
                shuttingDown = false;
            }
        }
    }
}
