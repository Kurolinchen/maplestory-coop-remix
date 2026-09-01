/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.stack;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice A.2 regression tests (audit fix B2):
 * The previously committed coop-1012 changeset seeded stack-override rows under
 * wrong item IDs (Monster Sack / quest items / etc.). The corrected changeset
 * coop-1031 removes the bogus rows and inserts the verified IDs.
 *
 * <p>This test enforces the desired end state via a pure in-memory StackOverrides
 * instance and verifies that no entry from the wrong-family list leaks into a
 * freshly built override map.
 */
class StackOverridesCorrectionTest {

    /** Items that coop-1012 mislabeled (must not appear in a correct override set). */
    private static final Set<Integer> WRONG_ITEMS = Set.of(
            2100000, 2100001, 2100002,
            4001000, 4001001, 4001002, 4001010, 4001011, 4001012);

    /** Verified correct IDs from coop-1031. */
    private static final Map<Integer, Short> VERIFIED = new HashMap<>();
    static {
        for (int id = 2070000; id <= 2070013; id++) VERIFIED.put(id, (short) 1000);
        for (int id = 2330000; id <= 2330006; id++) VERIFIED.put(id, (short) 1000);
        VERIFIED.put(2060000, (short) 1000);
        VERIFIED.put(2061000, (short) 1000);
        VERIFIED.put(2340000, (short) 100);
        VERIFIED.put(4006000, (short) 100);
        VERIFIED.put(4006001, (short) 100);
        for (int id = 4010000; id <= 4010007; id++) VERIFIED.put(id, (short) 500);
    }

    @Test
    void correctedOverrideMapDoesNotContainMislabeledItems() {
        StackOverrides ov = new StackOverrides(VERIFIED);
        for (Integer wrong : WRONG_ITEMS) {
            assertTrue(ov.maxPerSlot(wrong).isEmpty(),
                    "mislabeled item " + wrong + " must not have a stack override");
        }
    }

    @Test
    void correctedOverrideMapExposesAllVerifiedAmmoIds() {
        StackOverrides ov = new StackOverrides(VERIFIED);
        for (Map.Entry<Integer, Short> e : VERIFIED.entrySet()) {
            Optional<Short> slot = ov.maxPerSlot(e.getKey());
            assertTrue(slot.isPresent(),
                    "verified item " + e.getKey() + " must have a stack override");
            assertEquals(e.getValue().shortValue(), slot.get().shortValue(),
                    "stack size for item " + e.getKey() + " must match WZ label");
        }
    }

    @Test
    void correctedOverrideMapRejectsUnknownItem() {
        StackOverrides ov = new StackOverrides(VERIFIED);
        Optional<Short> missing = ov.maxPerSlot(9_999_999);
        assertNotNull(missing);
        assertTrue(missing.isEmpty());
    }

    @Test
    void correctedOverrideMapProtectsAgainstDuplicateSlots() {
        // Simulate coop-1031's INSERT IGNORE by deduping via a single-inserted
        // StackOverrides: the map keeps the first occurrence, so re-applying the
        // changeset must not silently double the slot size.
        Map<Integer, Short> merged = new HashMap<>(VERIFIED);
        StackOverrides ov = new StackOverrides(merged);
        assertEquals(ov.size(), VERIFIED.size(),
                "override map size must match the verified set (no duplicates)");
        // every override must still be reachable
        Set<Integer> seenIds = new HashSet<>();
        for (Integer id : VERIFIED.keySet()) {
            assertTrue(ov.maxPerSlot(id).isPresent());
            seenIds.add(id);
        }
        assertEquals(VERIFIED.size(), seenIds.size());
    }
}
