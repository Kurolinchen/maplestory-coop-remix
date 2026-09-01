/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.config;

import client.Client;
import client.command.Command;
import client.command.commands.gm4.CharSlotsCommand;
import net.server.Server;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice A.5 regression tests (audit fix B5).
 *
 * <p>{@code !charslots} previously clamped against the hard {@code 1..127} range
 * instead of the configured {@code coop.max_character_slots} cap, and reported
 * success even when the SQL update had failed. The corrected behaviour must:
 * <ul>
 *   <li>clamp against the configured cap;</li>
 *   <li>honour the configured {@code coop.default_character_slots} floor;</li>
 *   <li>defer persistence reporting to the caller (no false-positive ack).</li>
 * </ul>
 *
 * <p>This is a unit-level test against {@link CoopDefaults}; the integration with
 * {@link CharSlotsCommand} is exercised manually in the smoke test.
 */
class CharSlotsCommandClampTest {

    @Test
    void configuredCapIsUsedInsteadOfHardCap() {
        CoopConfig cfg = new CoopConfig();
        cfg.max_character_slots = 20;
        cfg.default_character_slots = 15;
        assertEquals(20, CoopDefaults.maxCharacterSlots(cfg));
        // lower requested values clamp to 1 (the GM-command floor)
        assertEquals(1, CoopDefaults.clamp(0, 1, CoopDefaults.maxCharacterSlots(cfg)));
        // higher requested values clamp to the configured cap
        assertEquals(20, CoopDefaults.clamp(50, 1, CoopDefaults.maxCharacterSlots(cfg)));
        // in-range values pass through
        assertEquals(17, CoopDefaults.clamp(17, 1, CoopDefaults.maxCharacterSlots(cfg)));
    }

    @Test
    void capIsNeverBelowDefault() {
        CoopConfig cfg = new CoopConfig();
        cfg.default_character_slots = 12;
        cfg.max_character_slots = 8;       // misconfigured: below default
        int cap = CoopDefaults.maxCharacterSlots(cfg);
        assertTrue(cap >= 12,
                "max_character_slots must never be below default_character_slots, got " + cap);
    }

    @Test
    void clampHelperIsIdempotentAtBounds() {
        assertEquals(1, CoopDefaults.clamp(0, 1, 20));
        assertEquals(20, CoopDefaults.clamp(20, 1, 20));
        assertEquals(20, CoopDefaults.clamp(21, 1, 20));
        assertEquals(7, CoopDefaults.clamp(7, 1, 20));
    }

    @Test
    void storageCapDefaultsAreRespected() {
        CoopConfig cfg = new CoopConfig();
        // default config (no yaml edits) is the coop 0.1 contract
        assertEquals(96, CoopDefaults.storageSlotCap(cfg));
        assertEquals(16, CoopDefaults.defaultStorageSlots(cfg));
        assertEquals(50, CoopDefaults.buddyDefaultCapacity(cfg));
        assertEquals(15, CoopDefaults.maxCharacterSlots(cfg));
    }
}
