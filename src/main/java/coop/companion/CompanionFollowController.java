/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.companion;

import client.Character;
import coop.config.CoopDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;
import server.maps.Portal;
import tools.PacketCreator;

import java.awt.Point;
import java.util.List;

/**
 * Slice C (Companion navigation): same-map follow plus bounded static-portal
 * following for the owner's observed map transitions.
 *
 * <p>Two independent mechanisms:
 * <ol>
 *   <li><b>same-map follow</b> — when the owner and companion are on the same
 *       map, the companion is repositioned toward a point behind the owner,
 *       clamped to a valid foothold. No pathfinding, no ropes/ladders.</li>
 *   <li><b>portal follow</b> — when the owner's map id changes between ticks,
 *       the companion looks for a static, scriptless, allowlisted portal in the
 *       map it just left whose target is the owner's new map, and traverses it
 *       directly through the lifecycle service.</li>
 * </ol>
 *
 * <p>The catch-up fallback is intentionally inert unless
 * {@code coop.companion.portal_fallback_enabled} is true, and even then it is
 * additionally constrained here (owner-only, one hop, grace window, cooldown).
 */
public final class CompanionFollowController {
    private static final Logger log = LoggerFactory.getLogger(CompanionFollowController.class);

    private final CompanionLifecycleService lifecycle = CompanionLifecycleService.getInstance();

    /** Outcome of a follow tick; carries the decision for diagnostics. */
    public record TickResult(boolean ownerStillValid, boolean moved, boolean transitioned,
                            boolean dismissed, String reason) {
        public static TickResult idle(String reason) {
            return new TickResult(true, false, false, false, reason);
        }
        public static TickResult followed() {
            return new TickResult(true, true, false, false, "followed owner");
        }
        public static TickResult transition(String reason) {
            return new TickResult(true, false, true, false, reason);
        }
        public static TickResult dismiss(String reason) {
            return new TickResult(false, false, false, true, reason);
        }
    }

    /** Tracks the last observed owner position/map per session (injected for testability). */
    public static final class OwnerTracker {
        private int lastMapId = -1;
        private Point lastPosition;
        private long lastMapChangeAt;

        public int lastMapId() { return lastMapId; }
        public Point lastPosition() { return lastPosition; }
        public long lastMapChangeAt() { return lastMapChangeAt; }

        void observe(Character owner) {
            if (owner == null) {
                return;
            }
            int mapId = owner.getMapId();
            Point pos = owner.getPosition();
            long now = System.currentTimeMillis();
            if (mapId != lastMapId) {
                lastMapId = mapId;
                lastMapChangeAt = now;
            }
            lastPosition = pos;
        }
    }

    /**
     * Executes one follow tick. Returns a description of what happened so the
     * caller can log it in {@code @companion status}.
     */
    public TickResult tick(CompanionSession session, Character owner, Character bot,
                           OwnerTracker tracker) {
        if (session == null || owner == null || bot == null || tracker == null) {
            return TickResult.idle("missing session/owner/bot");
        }
        if (!owner.isLoggedin()) {
            return TickResult.dismiss("owner no longer logged in");
        }
        if (bot.getMap() == null) {
            return TickResult.dismiss("companion has no map");
        }

        int previousMapId = tracker.lastMapId();
        tracker.observe(owner);
        int ownerMapId = tracker.lastMapId();

        // 1) Map transition: owner changed maps since the last observation.
        if (previousMapId >= 0 && previousMapId != ownerMapId) {
            return handleMapTransition(session, owner, bot, previousMapId, ownerMapId, tracker);
        }

        // 2) Same map: reposition behind the owner when too far away.
        return handleSameMapFollow(bot, owner);
    }

    private TickResult handleMapTransition(CompanionSession session, Character owner,
                                          Character bot, int fromMapId, int toMapId,
                                          OwnerTracker tracker) {
        if (!CoopDefaults.companionPortalFollowEnabled()) {
            return TickResult.dismiss(
                    "owner left the map and portal following is disabled");
        }
        if (!CompanionMapPolicy.isTransitionAllowed(fromMapId, toMapId)) {
            return TickResult.dismiss("owner moved to a map companions cannot enter (" + toMapId + ")");
        }
        long sinceChange = System.currentTimeMillis() - tracker.lastMapChangeAt();
        if (sinceChange > CoopDefaults.companionPortalFollowGraceMs()) {
            return TickResult.dismiss("portal follow window expired");
        }

        MapleMap destination = owner.getMap();
        if (destination == null || destination.getId() != toMapId) {
            return TickResult.dismiss("owner map mismatch");
        }
        // A scripted destination would re-run its entry script for the bot on
        // every hop, so refuse instead of following.
        if (CompanionMapPolicy.hasEntryScript(destination)) {
            return TickResult.dismiss(
                    "owner's map runs an entry script; companions cannot follow there");
        }

        // Find a static, scriptless portal in the map we just left whose target
        // is the owner's new map.
        MapleMap source = bot.getMap();
        Portal chosen = findFollowPortal(source, toMapId, tracker.lastPosition());
        if (chosen != null) {
            CompanionLifecycleService.Result result =
                    lifecycle.transferToMap(bot, source, destination);
            if (result.success()) {
                log.info("Companion followed owner through portal: owner={} companion={} {}->{}",
                        session.ownerCharacterId(), session.companionCharacterId(),
                        fromMapId, toMapId);
                return TickResult.transition("portal " + chosen.getName());
            }
            return TickResult.dismiss("portal transfer failed: " + result.reason());
        }

        // No direct portal: the bounded fallback is the last resort and is off
        // unless explicitly enabled.
        if (!CompanionMapPolicy.isFallbackAllowed(fromMapId, toMapId)) {
            return TickResult.dismiss("no safe portal to owner's map and fallback is disabled");
        }
        CompanionLifecycleService.Result result =
                lifecycle.transferToMap(bot, source, destination);
        if (result.success()) {
            log.warn("Companion used fallback catch-up: owner={} companion={} {}->{}",
                    session.ownerCharacterId(), session.companionCharacterId(),
                    fromMapId, toMapId);
            return TickResult.transition("fallback catch-up");
        }
        return TickResult.dismiss("fallback transfer failed: " + result.reason());
    }

    private TickResult handleSameMapFollow(Character bot, Character owner) {
        MapleMap map = bot.getMap();
        if (map.getId() != owner.getMapId()) {
            return TickResult.idle("not on the owner's map yet");
        }
        if (!bot.isAlive()) {
            return TickResult.idle("companion is not alive");
        }

        Point ownerPos = owner.getPosition();
        Point botPos = bot.getPosition();
        if (ownerPos == null || botPos == null) {
            return TickResult.idle("missing position");
        }
        double distance = ownerPos.distance(botPos);
        if (distance <= CoopDefaults.companionFollowStartDistance()) {
            return TickResult.idle("within follow distance");
        }

        int maxStep = CoopDefaults.companionFollowMaxStep();
        int offsetX = CoopDefaults.companionFollowOffsetX();
        // Stand slightly behind the owner, on the side the owner is facing away from.
        int targetX = ownerPos.x + (botPos.x <= ownerPos.x ? -offsetX : offsetX);
        int targetY = ownerPos.y;

        // Clamp the step so the companion never teleports across the map.
        int deltaX = clampStep(targetX - botPos.x, maxStep);
        int deltaY = clampStep(targetY - botPos.y, maxStep);
        Point candidate = new Point(botPos.x + deltaX, botPos.y + deltaY);

        Point ground = map.getGroundBelow(candidate);
        if (ground == null) {
            return TickResult.idle("no valid foothold at the follow target");
        }

        map.movePlayer(bot, ground);
        map.broadcastMessage(bot, buildMovePacket(bot, ground), false);
        return TickResult.followed();
    }

    /**
     * Builds a MOVE_PLAYER packet carrying a single absolute-movement fragment.
     *
     * <p>We serialise the fragment through the real {@link AbsoluteLifeMovement}
     * rather than hand-writing bytes, so the wire format stays in one place. The
     * companion has no client-side path, so a single absolute fragment is the
     * honest representation of "the server moved this character here".
     */
    private net.packet.Packet buildMovePacket(Character bot, Point target) {
        net.packet.OutPacket out = net.packet.OutPacket.create(net.opcodes.SendOpcode.MOVE_PLAYER);
        out.writeInt(bot.getId());
        out.writeInt(0);
        server.movement.AbsoluteLifeMovement move =
                new server.movement.AbsoluteLifeMovement(0, target, 0, bot.getStance());
        out.writeByte(1);               // fragment count
        move.serialize(out);
        return out;
    }

    private static int clampStep(int delta, int maxStep) {
        return Math.max(-maxStep, Math.min(maxStep, delta));
    }

    /**
     * Selects the portal the companion may use to reach {@code targetMapId}.
     *
     * <p>Deliberately conservative: only the portal CLOSEST to the owner's last
     * known position is considered (MapleMap does not expose a full portal
     * iterator). If that portal is not scriptless, open, within the configured
     * radius and aimed at the target map, the companion does not follow at all.
     * Failing safe here is the point — a missed follow costs the owner nothing,
     * whereas following through the wrong portal could skip content.
     */
    public Portal findFollowPortal(MapleMap source, int targetMapId, Point near) {
        if (source == null || near == null) {
            return null;
        }
        Portal closest = source.findClosestPortal(near);
        if (closest == null) {
            return null;
        }
        if (!CompanionMapPolicy.isPortalSafe(closest)) {
            return null;
        }
        if (closest.getTargetMapId() != targetMapId) {
            return null;
        }
        Point pos = closest.getPosition();
        if (pos == null) {
            return null;
        }
        if (pos.distance(near) > CoopDefaults.companionPortalSourceRadius()) {
            return null;
        }
        return closest;
    }
}
