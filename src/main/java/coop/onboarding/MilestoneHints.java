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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * coop 0.1 onboarding hints: yellow-message nudges shown on level-up. Backed by
 * table {@code coop_milestone_hints} (db/extensions/coop-1020) so designers can tune
 * messages and thresholds without touching Java.
 *
 * <p>Multiple rows with the same {@code min_level} are supported (each becomes its
 * own entry in the returned list). Hint deduplication per character lives in
 * {@link CharacterHintState}, not in this loader.
 */
public class MilestoneHints {
    private static final Logger log = LoggerFactory.getLogger(MilestoneHints.class);

    private static volatile NavigableMap<Integer, List<HintEntry>> hints;

    private MilestoneHints() {
    }

    public record HintEntry(String id, int minLevel, String text) {
    }

    public static NavigableMap<Integer, List<HintEntry>> all() {
        NavigableMap<Integer, List<HintEntry>> local = hints;
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
     * Returns every hint at or below the given level. The list is ordered by
     * (minLevel, id) so callers can present or persist them deterministically.
     */
    public static List<HintEntry> forLevel(int level) {
        List<HintEntry> all = new ArrayList<>();
        for (List<HintEntry> bucket : all().headMap(level, true).values()) {
            all.addAll(bucket);
        }
        all.sort(Comparator.comparingInt(HintEntry::minLevel).thenComparing(HintEntry::id));
        return Collections.unmodifiableList(all);
    }

    private static NavigableMap<Integer, List<HintEntry>> loadFromDb() {
        NavigableMap<Integer, List<HintEntry>> loaded = new TreeMap<>();
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
                    loaded.computeIfAbsent(minLevel, k -> new ArrayList<>())
                            .add(new HintEntry(id, minLevel, text));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load coop_milestone_hints; hint table is unavailable", e);
            return null;
        }
        int count = loaded.values().stream().mapToInt(List::size).sum();
        log.info("Loaded {} coop milestone hint(s) across {} level bucket(s)", count, loaded.size());
        return loaded;
    }
}
