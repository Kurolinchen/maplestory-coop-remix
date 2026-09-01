/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/

package coop.expedition;

import config.YamlConfig;
import coop.config.CoopDefaults;
import server.expeditions.ExpeditionType;

import java.util.Map;

/**
 * Solo-first expedition sizing (Milestone 0.1, DECISIONS.md D8): any boss expedition can be
 * entered/started solo when {@code USE_ENABLE_SOLO_EXPEDITIONS} is on; per-type overrides may
 * be configured in the {@code coop.expedition_min_size} map. Pure function for testability.
 */
public final class ExpeditionRules {
    private ExpeditionRules() {
    }

    public static int effectiveMinSize(String typeName, int upstreamMinSize, boolean soloExpeditions,
                                       Map<String, Integer> overrides) {
        if (overrides != null && typeName != null) {
            Integer override = overrides.get(typeName);
            if (override != null && override > 0) {
                return Math.min(override, upstreamMinSize);
            }
        }
        return soloExpeditions ? 1 : upstreamMinSize;
    }

    public static int effectiveMinSize(ExpeditionType type, int upstreamMinSize) {
        return effectiveMinSize(type.name(), upstreamMinSize,
                YamlConfig.config.server.USE_ENABLE_SOLO_EXPEDITIONS,
                CoopDefaults.cfg().expedition_min_size);
    }
}
