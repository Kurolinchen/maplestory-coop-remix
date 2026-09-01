/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/

package coop.config;

import config.YamlConfig;

/**
 * Null-safe, clamped accessors for the custom "coop:" config block (DECISIONS.md D7).
 * The one-argument variants are pure functions for unit testing; the zero-argument
 * variants read the live config (falling back to field defaults when the block is absent).
 */
public final class CoopDefaults {
    private CoopDefaults() {
    }

    public static CoopConfig cfg() {
        CoopConfig coop = YamlConfig.config == null ? null : YamlConfig.config.coop;
        return coop == null ? new CoopConfig() : coop;
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static int defaultCharacterSlots(CoopConfig cfg) {
        return clamp(cfg.default_character_slots, 1, 127);
    }

    public static int defaultCharacterSlots() {
        return defaultCharacterSlots(cfg());
    }

    /** Cap is never below the default, so new accounts can never start above the cap. */
    public static int maxCharacterSlots(CoopConfig cfg) {
        int def = defaultCharacterSlots(cfg);
        return Math.max(def, clamp(cfg.max_character_slots, def, 127));
    }

    public static int maxCharacterSlots() {
        return maxCharacterSlots(cfg());
    }

    public static int defaultInventorySlots(CoopConfig cfg) {
        return clamp(cfg.default_inventory_slots, 4, 96);
    }

    public static int defaultInventorySlots() {
        return defaultInventorySlots(cfg());
    }

    public static int storageSlotCap(CoopConfig cfg) {
        return clamp(cfg.storage_slot_cap, 48, 127);
    }

    public static int storageSlotCap() {
        return storageSlotCap(cfg());
    }

    public static int defaultStorageSlots(CoopConfig cfg) {
        return clamp(cfg.default_storage_slots, 4, storageSlotCap(cfg));
    }

    public static int defaultStorageSlots() {
        return defaultStorageSlots(cfg());
    }

    public static int buddyDefaultCapacity(CoopConfig cfg) {
        return clamp(cfg.buddy_default_capacity, 20, 127);
    }

    public static int buddyDefaultCapacity() {
        return buddyDefaultCapacity(cfg());
    }
}
