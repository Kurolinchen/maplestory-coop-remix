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
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * coop 0.1 onboarding hints: yellow-message nudges shown on level-up. Backed by
 * table {@code coop_milestone_hints} (db/extensions/coop-1020 and coop-1033). The
 * optional {@code job_filter} column restricts a row to a set of job ids so class-
 * specific guidance can be curated in data (audit B6).
 *
 * <p>Per-character hint deduplication lives in {@link CharacterHintState}, not
 * here. Lookup is lazy + cached per process.
 */
public class MilestoneHints {
    private static final Logger log = LoggerFactory.getLogger(MilestoneHints.class);

    private static volatile NavigableMap<Integer, List<HintEntry>> hints;

    private MilestoneHints() {
    }

    public record HintEntry(String id, int minLevel, String text, Set<Integer> jobFilter) {
        public boolean appliesToJob(int jobId) {
            return jobFilter == null || jobFilter.isEmpty() || jobFilter.contains(jobId);
        }
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

    /** Backwards-compatible variant: returns every hint at or below the level (no job filter). */
    public static List<HintEntry> forLevel(int level) {
        return forLevel(level, -1);
    }

    /**
     * Returns every hint at or below the given level whose job filter matches
     * {@code jobId}. {@code jobId <= 0} disables the job filter.
     */
    public static List<HintEntry> forLevel(int level, int jobId) {
        List<HintEntry> all = new ArrayList<>();
        for (List<HintEntry> bucket : all().headMap(level, true).values()) {
            for (HintEntry e : bucket) {
                if (e.appliesToJob(jobId)) {
                    all.add(e);
                }
            }
        }
        all.sort(Comparator.comparingInt(HintEntry::minLevel).thenComparing(HintEntry::id));
        return Collections.unmodifiableList(all);
    }

    private static NavigableMap<Integer, List<HintEntry>> loadFromDb() {
        NavigableMap<Integer, List<HintEntry>> loaded = new TreeMap<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT id, min_level, hint_text, job_filter FROM coop_milestone_hints")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    int minLevel = rs.getInt("min_level");
                    String text = rs.getString("hint_text");
                    String jobFilter = rs.getString("job_filter");
                    if (id == null || id.isBlank() || text == null || text.isBlank() || minLevel <= 0) {
                        log.warn("coop_milestone_hints: skipping invalid row (id={}, min_level={})",
                                id, minLevel);
                        continue;
                    }
                    Set<Integer> jobs = parseJobFilter(jobFilter);
                    loaded.computeIfAbsent(minLevel, k -> new ArrayList<>())
                            .add(new HintEntry(id, minLevel, text, jobs));
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

    static Set<Integer> parseJobFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptySet();
        }
        Set<Integer> out = new HashSet<>();
        for (String token : raw.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            try {
                out.add(Integer.parseInt(t));
            } catch (NumberFormatException nfe) {
                log.warn("coop_milestone_hints: skipping non-numeric job id '{}'", t);
            }
        }
        return out;
    }
}
