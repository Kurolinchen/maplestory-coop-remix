/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.earlygame;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Early Game Remix (coop 0.1b): turns the early-game telemetry into balance
 * numbers.
 *
 * <p>Reads {@code coop_early_game_exp_log} and reports, per level, how much EXP
 * was gained and how fast. The rate is derived from the wall-clock span
 * covered by that level's samples, which is the honest approximation available
 * from the log: it measures the level as observed, not a laboratory average.
 */
public final class TrainingAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(TrainingAnalyzer.class);

    private TrainingAnalyzer() {
    }

    /** Aggregated telemetry for one level. */
    public record LevelStats(int level, long totalExp, long samples, double expPerHour) {
    }

    /**
     * Aggregates the telemetry for a level range. Returns an empty list when
     * the table is missing or no data exists yet; it never throws.
     */
    public static List<LevelStats> analyze(int minLevel, int maxLevel) {
        if (minLevel > maxLevel) {
            return List.of();
        }
        List<LevelStats> result = new ArrayList<>();
        String sql = "SELECT level, SUM(gained_exp) AS total_exp, COUNT(*) AS samples, "
                + "MIN(logged_at) AS first_at, MAX(logged_at) AS last_at "
                + "FROM coop_early_game_exp_log "
                + "WHERE level BETWEEN ? AND ? "
                + "GROUP BY level ORDER BY level";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, minLevel);
            ps.setInt(2, maxLevel);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int level = rs.getInt("level");
                    long totalExp = rs.getLong("total_exp");
                    long samples = rs.getLong("samples");
                    long first = timestampOrZero(rs, "first_at");
                    long last = timestampOrZero(rs, "last_at");
                    result.add(new LevelStats(level, totalExp, samples,
                            expPerHour(totalExp, first, last)));
                }
            }
        } catch (SQLException | RuntimeException e) {
            // A balance query must never take the server down: an uninitialized
            // pool or a missing table degrades to "no data".
            log.error("Early-game telemetry analysis failed: {}", e.getMessage());
            return List.of();
        }
        return List.copyOf(result);
    }

    /** EXP per hour over the observed span; 0 when the span is unusable. */
    public static double expPerHour(long totalExp, long firstAtMillis, long lastAtMillis) {
        if (totalExp <= 0 || firstAtMillis <= 0 || lastAtMillis <= firstAtMillis) {
            return 0.0;
        }
        double hours = (lastAtMillis - firstAtMillis) / 3_600_000.0;
        if (hours <= 0.0) {
            return 0.0;
        }
        return totalExp / hours;
    }

    private static long timestampOrZero(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(column);
        return ts == null ? 0L : ts.getTime();
    }
}
