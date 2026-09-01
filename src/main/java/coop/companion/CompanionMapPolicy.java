/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.companion;

import coop.config.CoopDefaults;
import server.maps.Portal;

import java.util.List;

/**
 * Slice C (Companion navigation): pure policy layer deciding WHERE a companion
 * may exist and WHICH portal transitions it may follow.
 *
 * <p>Design intent — companions must never become a warp exploit or a way to
 * satisfy party-size checks in content designed for real players:
 * <ul>
 *   <li>only explicit allowlisted ordinary maps are eligible;</li>
 *   <li>script portals are never executed;</li>
 *   <li>the catch-up fallback is off by default and additionally guarded by a
 *       caller-side chain of checks (see {@link CompanionFollowController});</li>
 * </ul>
 *
 * <p>All methods are pure so they can be unit tested without a live world.
 */
public final class CompanionMapPolicy {
    private CompanionMapPolicy() {
    }

    public static boolean isMapAllowed(int mapId) {
        List<Integer> allowed = CoopDefaults.companionAllowedMapIds();
        return !allowed.isEmpty() && allowed.contains(mapId);
    }

    /** Companion-only maps (event lobbies, instances, etc.) are always rejected. */
    public static boolean isBlocked(int mapId) {
        return isSpecialMapId(mapId);
    }

    /**
     * Whether a map runs a script when a character enters it.
     *
     * <p>{@code MapleMap.addPlayer} executes {@code onFirstUserEnter} and
     * {@code onUserEnter}. A companion is attached through that same method, so
     * allowing a scripted map means every spawn AND every portal-follow hop
     * re-runs the script for the bot. Some entry scripts grant progress
     * (e.g. {@code explorationPoint} -> quest 29005), which turns repeated
     * spawn/dismiss cycles into a reward farm. Companion maps must therefore be
     * script-free.
     */
    public static boolean hasEntryScript(server.maps.MapleMap map) {
        if (map == null) {
            return false;
        }
        String onEnter = map.getOnUserEnter();
        String onFirstEnter = map.getOnFirstUserEnter();
        return (onEnter != null && !onEnter.isEmpty())
                || (onFirstEnter != null && !onFirstEnter.isEmpty());
    }

    /**
     * Whether a companion may be attached to this actual map instance: the id
     * must be eligible AND the map must not run entry scripts.
     */
    public static boolean canHost(server.maps.MapleMap map) {
        if (map == null) {
            return false;
        }
        return !isBlocked(map.getId()) && !hasEntryScript(map);
    }

    /**
     * True for map-id ranges that must never host a companion: event/PQ
     * instances, boss maps, dojo, and other instanced content where an extra
     * body could satisfy a party-size check or duplicate a reward.
     */
    public static boolean isSpecialMapId(int mapId) {
        // Event / PQ instance ranges and common instanced content prefixes.
        return mapId >= 910_000_000      // PQ lobbies / event instances
                || mapId == 809_000_101  // example instanced field
                || (mapId >= 925_000_000 && mapId <= 926_000_000)  // dojo
                || (mapId >= 950_000_000 && mapId <= 969_000_000); // event fields
    }

    /**
     * Whether a portal is safe for the companion to traverse autonomously.
     * Rejects script portals (their side effects belong to the real player flow)
     * and closed portals.
     */
    public static boolean isPortalSafe(Portal portal) {
        if (portal == null) {
            return false;
        }
        if (!portal.getPortalState()) {
            return false;
        }
        if (portal.getScriptName() != null && !portal.getScriptName().isEmpty()) {
            // Never execute a portal script on behalf of a bot: scripts can
            // grant rewards, start quests or move whole parties.
            return false;
        }
        return true;
    }

    /**
     * Whether the owner-observed transition from {@code fromMapId} to
     * {@code toMapId} may be followed with a direct (non-teleport) portal hop.
     */
    public static boolean isTransitionAllowed(int fromMapId, int toMapId) {
        if (fromMapId == toMapId) {
            return false;
        }
        if (isBlocked(fromMapId) || isBlocked(toMapId)) {
            return false;
        }
        // Both endpoints must be explicitly allowlisted; an empty allowlist
        // means no map is ever eligible.
        return isMapAllowed(fromMapId) && isMapAllowed(toMapId);
    }

    /**
     * Whether the tightly-bounded catch-up fallback may be used. Always false
     * unless the operator has explicitly opted in AND both maps pass the
     * standard transition checks.
     */
    public static boolean isFallbackAllowed(int fromMapId, int toMapId) {
        return CoopDefaults.companionPortalFallbackEnabled()
                && isTransitionAllowed(fromMapId, toMapId);
    }
}
