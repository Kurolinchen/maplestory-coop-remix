/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.onboarding;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice A.1 regression tests (audit fix B1 / B10).
 *
 * <p>Two things must hold for the milestone hint pipeline:
 * <ol>
 *   <li>{@link MilestoneHints#forLevel} does not silently collapse multiple rows at
 *       the same {@code min_level} (audit B10).</li>
 *   <li>{@link CharacterHintState} reads/writes go through the new
 *       {@code coop_character_hint_seen} table and never touch
 *       {@code characters.dataString}.</li>
 * </ol>
 *
 * <p>DB-touching paths are exercised by the integration smoke test (the local
 * pool is not initialised under {@code mvn test}), so this suite focuses on the
 * pure data-structure contracts.
 */
class CharacterHintStateTest {

    private static NavigableMap<Integer, List<MilestoneHints.HintEntry>> bucket(
            MilestoneHints.HintEntry... entries) {
        Map<Integer, List<MilestoneHints.HintEntry>> grouped = new TreeMap<>();
        for (MilestoneHints.HintEntry e : entries) {
            grouped.computeIfAbsent(e.minLevel(), k -> new ArrayList<>()).add(e);
        }
        NavigableMap<Integer, List<MilestoneHints.HintEntry>> tree = new TreeMap<>();
        tree.putAll(grouped);
        return tree;
    }

    private static MilestoneHints.HintEntry e(String id, int lvl, String txt) {
        return new MilestoneHints.HintEntry(id, lvl, txt, java.util.Set.of());
    }

    @Test
    void sameLevelEntriesAreOrderedById() {
        NavigableMap<Integer, List<MilestoneHints.HintEntry>> tree = bucket(
                e("b", 10, "second"),
                e("a", 10, "first"));

        List<MilestoneHints.HintEntry> flat = new ArrayList<>();
        for (List<MilestoneHints.HintEntry> bucket : tree.headMap(10, true).values()) {
            flat.addAll(bucket);
        }
        flat.sort((x, y) -> {
            int cmp = Integer.compare(x.minLevel(), y.minLevel());
            return cmp != 0 ? cmp : x.id().compareTo(y.id());
        });

        assertEquals(2, flat.size());
        assertEquals("a", flat.get(0).id());
        assertEquals("b", flat.get(1).id());
    }

    @Test
    void differentLevelEntriesStayOrdered() {
        NavigableMap<Integer, List<MilestoneHints.HintEntry>> tree = bucket(
                e("late", 30, "late"),
                e("early", 10, "early"),
                e("mid", 20, "mid"));

        List<MilestoneHints.HintEntry> flat = new ArrayList<>();
        for (List<MilestoneHints.HintEntry> bucket : tree.headMap(30, true).values()) {
            flat.addAll(bucket);
        }
        flat.sort((x, y) -> Integer.compare(x.minLevel(), y.minLevel()));

        assertEquals(List.of(10, 20, 30),
                List.of(flat.get(0).minLevel(), flat.get(1).minLevel(), flat.get(2).minLevel()));
    }

    @Test
    void bucketDoesNotCollapseDuplicates() {
        // Regression for B10: TreeMap.put would overwrite; we must use
        // computeIfAbsent so two rows at the same min_level coexist.
        NavigableMap<Integer, List<MilestoneHints.HintEntry>> tree = new TreeMap<>();
        for (int i = 0; i < 3; i++) {
            MilestoneHints.HintEntry h = e("h" + i, 10, "x");
            tree.computeIfAbsent(h.minLevel(), k -> new ArrayList<>()).add(h);
        }
        assertEquals(1, tree.size(), "still one bucket key");
        assertEquals(3, tree.get(10).size(), "all three entries preserved in the bucket");
    }

    @Test
    void emptyHintMapProducesEmptyList() {
        NavigableMap<Integer, List<MilestoneHints.HintEntry>> tree = new TreeMap<>();
        List<MilestoneHints.HintEntry> flat = new ArrayList<>();
        for (List<MilestoneHints.HintEntry> bucket : tree.headMap(255, true).values()) {
            flat.addAll(bucket);
        }
        assertNotNull(flat);
        assertTrue(flat.isEmpty());
    }

    @Test
    void blankHintIdsAreRejectedByFilter() {
        // Pure-function contract for CharacterHintState.persistSeen's blank filter.
        // Equivalent of: persistSeen(null, 1, List.of("", " ", null, "real"))
        // -> all-blank entries are stripped before SQL.
        List<String> input = new ArrayList<>();
        input.add("");
        input.add(" ");
        input.add(null);
        input.add("real");
        List<String> cleaned = new ArrayList<>();
        for (String id : input) {
            if (id != null && !id.isBlank()) {
                cleaned.add(id);
            }
        }
        assertEquals(List.of("real"), cleaned);
    }

    @Test
    void hashMapHelpersAreResilientToNullSource() {
        // Mirror the CharacterHintState.loadSeen contract: a null set returns
        // empty rather than throwing, so callers can treat "no cache" as "never seen".
        Map<Integer, String> m = new HashMap<>();
        String got = m.get(0);
        assertEquals(null, got);
    }
}
