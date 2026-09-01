/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/

package coop.config;

import java.util.HashMap;
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
}
