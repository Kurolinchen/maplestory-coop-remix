/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.companion;

import client.Character;
import net.opcodes.SendOpcode;
import net.packet.OutPacket;
import net.packet.Packet;
import server.maps.Foothold;
import server.maps.MapleMap;
import server.movement.AbsoluteLifeMovement;

import java.awt.Point;
import java.util.Optional;

/** Grounds headless companions and emits complete server-authored movement packets. */
final class CompanionMovementService {
    record GroundedPosition(Point position, int footholdId) {
    }

    Optional<GroundedPosition> resolveGround(MapleMap map, Point anchor) {
        if (map == null || anchor == null || map.getFootholds() == null) {
            return Optional.empty();
        }
        Point search = new Point(anchor.x, anchor.y - 14);
        Foothold foothold = map.getFootholds().findBelow(search);
        Point ground = map.getPointBelow(search);
        if (foothold == null || ground == null) {
            return Optional.empty();
        }
        return Optional.of(new GroundedPosition(
                new Point(ground.x, ground.y - 1), foothold.getId()));
    }

    void moveAndBroadcast(MapleMap map, Character bot, GroundedPosition ground) {
        map.movePlayer(bot, ground.position());
        map.broadcastMessage(bot, buildMovePacket(bot, ground), false);
    }

    Packet buildMovePacket(Character bot, GroundedPosition ground) {
        OutPacket out = OutPacket.create(SendOpcode.MOVE_PLAYER);
        out.writeInt(bot.getId());
        out.writeInt(0);
        out.writeByte(1);

        AbsoluteLifeMovement move = new AbsoluteLifeMovement(
                0, ground.position(), 0, bot.getStance());
        move.setPixelsPerSecond(new Point(0, 0));
        move.setFh(ground.footholdId());
        move.serialize(out);
        return out;
    }
}
