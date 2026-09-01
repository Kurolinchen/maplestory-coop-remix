/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/

package coop.config;

import config.YamlConfig;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * Default world travel-rate fallback (higher = faster transports). The world
     * config takes precedence; this is only used when a world leaves its
     * travel_rate at the upstream default of 0. Clamped to a safe positive range
     * so a missing/negative value still yields a usable transport timer.
     */
    public static int travelRateDefault(CoopConfig cfg) {
        return clamp(cfg.travel_rate_default, 1, 1000);
    }

    public static int travelRateDefault() {
        return travelRateDefault(cfg());
    }

    // ------------------------------------------------------------------
    // Companion Bot (Slice B). Every accessor is null-safe: an absent
    // coop.companion block or an absent key falls back to the (disabled)
    // field default rather than throwing.
    // ------------------------------------------------------------------

    /**
     * Pure helper: resolves the companion sub-block, tolerating a null block so
     * a missing {@code coop.companion:} section degrades to the field defaults.
     */
    static CoopConfig.CompanionConfig companion(CoopConfig cfg) {
        return cfg.companion == null ? new CoopConfig.CompanionConfig() : cfg.companion;
    }

    private static CoopConfig.CompanionConfig companion() {
        return companion(cfg());
    }

    public static boolean companionEnabled() {
        return companion().enabled;
    }

    public static int companionMaxActivePerOwner() {
        return clamp(companion().max_active_per_owner, 1, 6);
    }

    public static int companionMaxActivePerAccount() {
        return clamp(companion().max_active_per_account, 1, 6);
    }

    /**
     * Returns the allowed map list. An empty list means NO map is eligible, so
     * the caller must treat empty as "companions cannot spawn anywhere" rather
     * than "everywhere".
     */
    public static List<Integer> companionAllowedMapIds() {
        return companionAllowedMapIds(cfg());
    }

    /** Pure variant of {@link #companionAllowedMapIds()} for unit tests. */
    public static List<Integer> companionAllowedMapIds(CoopConfig cfg) {
        CoopConfig.CompanionConfig c = companion(cfg);
        if (c.allowed_map_ids == null) {
            return List.of();
        }
        List<Integer> copy = new ArrayList<>();
        for (Integer id : c.allowed_map_ids) {
            if (id != null && id > 0) {
                copy.add(id);
            }
        }
        return List.copyOf(copy);
    }

    public static int companionAllowedJobTier() {
        return clamp(companion().allowed_job_tier, 0, 4);
    }

    public static int companionTickMs() {
        return clamp(companion().tick_ms, 100, 10_000);
    }

    public static int companionFollowStartDistance() {
        return clamp(companion().follow_start_distance, 0, 100_000);
    }

    public static int companionFollowOffsetX() {
        return clamp(companion().follow_offset_x, 0, 10_000);
    }

    public static int companionFollowMaxStep() {
        return clamp(companion().follow_max_step, 1, 10_000);
    }

    public static int companionSameMapRecoveryDistance() {
        return clamp(companion().same_map_recovery_distance, 1, 1_000_000);
    }

    public static boolean companionPortalFollowEnabled() {
        return companion().portal_follow_enabled;
    }

    /** Off by default; enabling requires the Slice C audit sign-off. */
    public static boolean companionPortalFallbackEnabled() {
        return companion().portal_fallback_enabled;
    }

    public static int companionPortalFollowGraceMs() {
        return clamp(companion().portal_follow_grace_ms, 0, 60_000);
    }

    public static int companionPortalFallbackCooldownMs() {
        return clamp(companion().portal_fallback_cooldown_ms, 0, 600_000);
    }

    public static int companionPortalSourceRadius() {
        return clamp(companion().portal_source_radius, 1, 100_000);
    }

    public static int companionAttackIntervalMs() {
        return clamp(companion().attack_interval_ms, 100, 60_000);
    }

    public static int companionTargetRange() {
        return clamp(companion().target_range, 1, 100_000);
    }

    public static int companionTargetLevelDelta() {
        return clamp(companion().target_level_delta, 0, 200);
    }

    public static boolean companionAllowBosses() {
        return companion().allow_bosses;
    }

    public static double companionOutgoingDamageMinRatio() {
        return companionOutgoingDamageMinRatio(cfg());
    }

    /** Pure variant for unit tests. */
    public static double companionOutgoingDamageMinRatio(CoopConfig cfg) {
        return clampRatio(companion(cfg).outgoing_damage_min_ratio);
    }

    public static double companionOutgoingDamageMaxRatio() {
        return companionOutgoingDamageMaxRatio(cfg());
    }

    /** Pure variant for unit tests. */
    public static double companionOutgoingDamageMaxRatio(CoopConfig cfg) {
        return clampRatio(companion(cfg).outgoing_damage_max_ratio);
    }

    public static double companionOutgoingDamageHpCapRatio() {
        return companionOutgoingDamageHpCapRatio(cfg());
    }

    /** Pure variant for unit tests. */
    public static double companionOutgoingDamageHpCapRatio(CoopConfig cfg) {
        return clampRatio(companion(cfg).outgoing_damage_hp_cap_ratio);
    }

    public static int companionOutgoingDamageAbsoluteCap() {
        return clamp(companion().outgoing_damage_absolute_cap, 1, 10_000_000);
    }

    public static boolean companionIncomingDamageEnabled() {
        return companion().incoming_damage_enabled;
    }

    public static int companionIncomingDamageIntervalMs() {
        return clamp(companion().incoming_damage_interval_ms, 100, 60_000);
    }

    public static int companionIncomingContactRange() {
        return clamp(companion().incoming_contact_range, 1, 100_000);
    }

    public static int companionIncomingDamageMin() {
        return clamp(companion().incoming_damage_min, 0, 10_000_000);
    }

    public static double companionIncomingDamageMaxRatio() {
        return clampRatio(companion().incoming_damage_max_ratio);
    }

    public static double companionHpPotionRatio() {
        return clampRatio(companion().hp_potion_ratio);
    }

    public static double companionMpPotionRatio() {
        return clampRatio(companion().mp_potion_ratio);
    }

    public static int companionConsumeIntervalMs() {
        return clamp(companion().consume_interval_ms, 100, 60_000);
    }

    public static List<Integer> companionAllowedHpPotions() {
        return positiveIds(companion().allowed_hp_potions);
    }

    public static List<Integer> companionAllowedMpPotions() {
        return positiveIds(companion().allowed_mp_potions);
    }

    /** Looting is opt-in: the default keeps companion looting disabled. */
    public static boolean companionLootEnabledDefault() {
        return companion().loot_enabled_default;
    }

    public static int companionLootRadius() {
        return clamp(companion().loot_radius, 1, 100_000);
    }

    public static int companionLootIntervalMs() {
        return clamp(companion().loot_interval_ms, 100, 60_000);
    }

    public static boolean companionDeathDismiss() {
        return companion().death_dismiss;
    }

    public static int companionDeathDelayMs() {
        return clamp(companion().death_delay_ms, 0, 600_000);
    }

    private static double clampRatio(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static List<Integer> positiveIds(List<Integer> source) {
        if (source == null) {
            return List.of();
        }
        List<Integer> copy = new ArrayList<>();
        for (Integer id : source) {
            if (id != null && id > 0) {
                copy.add(id);
            }
        }
        return List.copyOf(copy);
    }
}
