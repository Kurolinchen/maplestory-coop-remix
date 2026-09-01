/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/

package coop.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom "coop:" block of config.yaml (see DECISIONS.md D7). Parsed by yamlbeans via
 * config/YamlConfig.java; public fields map 1:1 to snake_case yaml keys. Field defaults are
 * the Co-op Remix QoL values, so the server keeps them even if the block or a key is absent.
 */
public class CoopConfig {
    public int default_character_slots = 15;
    public int max_character_slots = 15;
    public int default_inventory_slots = 32;
    public int default_storage_slots = 16;
    public int storage_slot_cap = 96;
    public int travel_rate_default = 10;
    public int buddy_default_capacity = 50;
    public Map<String, Integer> expedition_min_size = new HashMap<>();

    // Companion Bot (Slice B). Nested config object, all accessors go through CoopDefaults.
    public CompanionConfig companion = new CompanionConfig();

    // Early Game Remix (levels 1-30). All accessors go through CoopDefaults.
    public EarlyGameConfig early_game = new EarlyGameConfig();

    public static class EarlyGameConfig {
        /** Extra SP granted to beginner jobs at creation so Three Snails /
            Recovery / Nimble Feet are usable during levels 1-10. */
        public int beginner_sp_bonus = 3;
        /** Grant the data-driven first-job kit on first job advancement. */
        public boolean first_job_kits_enabled = true;
        /** Enable the @training guide (player command). */
        public boolean training_guide_enabled = true;
        /** How far above/below the player level a recommended spot may be. */
        public int training_level_delta = 10;
        /** Maximum number of recommended spots listed by @training. */
        public int training_max_results = 5;
        /** Early-game EXP telemetry is opt-in (dev/balance use). */
        public boolean telemetry_enabled = false;
        public int telemetry_flush_interval_seconds = 60;
        public int telemetry_queue_capacity = 100_000;
        public int telemetry_batch_size = 1_000;
        public int telemetry_shutdown_timeout_seconds = 5;
    }

    public static class CompanionConfig {
        // Master switch. Disabled by default; ops opt in via config.yaml.
        public boolean enabled = false;
        public int max_active_per_owner = 1;
        public int max_active_per_account = 1;
        // Empty list = no map is eligible; companion stays disabled.
        public List<Integer> allowed_map_ids = new ArrayList<>();
        // Only first-job characters can be companions in MVP.
        public int allowed_job_tier = 1;
        // AI tick cadence (ms). Conservative to keep the server responsive.
        public int tick_ms = 500;
        // Follow-distance thresholds.
        public int follow_start_distance = 180;
        public int follow_offset_x = 60;
        public int follow_max_step = 120;
        public int same_map_recovery_distance = 700;
        // Cross-map navigation
        public boolean portal_follow_enabled = true;
        public boolean portal_fallback_enabled = false; // OFF by default; Slice C audit
        public int portal_follow_grace_ms = 2000;
        public int portal_fallback_cooldown_ms = 10_000;
        public int portal_source_radius = 250;
        // Combat bounds (Slice D)
        public int attack_interval_ms = 1200;
        public int target_range = 350;
        public int target_level_delta = 10;
        public boolean allow_bosses = false;
        public double outgoing_damage_min_ratio = 0.70;
        public double outgoing_damage_max_ratio = 1.00;
        public double outgoing_damage_hp_cap_ratio = 0.25;
        public int outgoing_damage_absolute_cap = 100_000;
        public boolean incoming_damage_enabled = true;
        public int incoming_damage_interval_ms = 1800;
        public int incoming_contact_range = 90;
        public int incoming_damage_min = 1;
        public double incoming_damage_max_ratio = 0.35;
        // Consumables
        public double hp_potion_ratio = 0.45;
        public double mp_potion_ratio = 0.25;
        /** Minimum gap between two potion uses; without it a hurt companion
            drains its whole stack within a few ticks. */
        public int consume_interval_ms = 1500;
        public List<Integer> allowed_hp_potions = new ArrayList<>();
        public List<Integer> allowed_mp_potions = new ArrayList<>();
        // Looting (Slice E)
        public boolean loot_enabled_default = false;
        public int loot_radius = 180;
        public int loot_interval_ms = 800;
        // Death behaviour
        public boolean death_dismiss = true;
        public int death_delay_ms = 3000;
    }
}
