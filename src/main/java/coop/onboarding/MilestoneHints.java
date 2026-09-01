/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.onboarding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * coop 0.1 onboarding hints: yellow-message nudges shown on level-up. Backed by
 * table {@code coop_milestone_hints} (db/extensions/coop-1020) so designers can tune
 * messages and thresholds without touching Java.
 *
 * <p>Lookup is by minimum level. Once a hint's id has been delivered it is the
 * caller's responsibility to persist it on the character to suppress re-sending
 * (mirrors the pattern already used for the existing starter-party hint).
 */
public class MilestoneHints {
    private static final Logger log = LoggerFactory.getLogger(MilestoneHints.class);

    private static volatile NavigableMap<Integer, HintEntry> hints;

    private MilestoneHints() {
    }

    public record HintEntry(String id, int minLevel, String text) {
    }

    public static NavigableMap<Integer, HintEntry> all() {
        NavigableMap<Integer, HintEntry> local = hints;
        if (local == null) {
            synchronized (MilestoneHints.class) {
                local = hints;
                if (local == null) {
                    local = loadFromDb();
                    if (local == null) {
                        local = new TreeMap<>();
                    }
                    hints = local;
                }
            }
        }
        return local;
    }

    /**
     * Returns every hint at or below the given level, useful for "show everything that has unlocked so far".
     */
    public static java.util.List<HintEntry> forLevel(int level) {
        return all().headMap(level, true).values().stream().toList();
    }

    private static NavigableMap<Integer, HintEntry> loadFromDb() {
        NavigableMap<Integer, HintEntry> loaded = new TreeMap<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT id, min_level, hint_text FROM coop_milestone_hints")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    int minLevel = rs.getInt("min_level");
                    String text = rs.getString("hint_text");
                    if (id == null || id.isBlank() || text == null || text.isBlank() || minLevel <= 0) {
                        log.warn("coop_milestone_hints: skipping invalid row (id={}, min_level={})",
                                id, minLevel);
                        continue;
                    }
                    loaded.merge(minLevel, new HintEntry(id, minLevel, text),
                            (a, b) -> b /* keep last on duplicate min_level */);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load coop_milestone_hints; hint table is unavailable", e);
            return null;
        }
        log.info("Loaded {} coop milestone hint(s)", loaded.size());
        return loaded;
    }
}
