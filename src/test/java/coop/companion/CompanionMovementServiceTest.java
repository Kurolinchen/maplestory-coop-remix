/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.companion;

import client.Character;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompanionMovementServiceTest {
    private final CompanionMovementService movement = new CompanionMovementService();

    @Test
    void resolvesGroundAndFootholdTogether() {
        MapleMap map = mapWithFlatFoothold(77, 100);

        CompanionMovementService.GroundedPosition ground = movement
                .resolveGround(map, new Point(20, 0)).orElseThrow();

        assertEquals(new Point(20, 99), ground.position());
        assertEquals(77, ground.footholdId());
    }

    @Test
    void missingInputsOrFootholdFailSafely() {
        assertTrue(movement.resolveGround(null, new Point()).isEmpty());
        assertTrue(movement.resolveGround(new MapleMap(1, 0, 1, 0, 1), null).isEmpty());

        MapleMap empty = new MapleMap(1, 0, 1, 0, 1);
        empty.setFootholds(new FootholdTree(
                new Point(-1000, -1000), new Point(1000, 1000)));
        assertTrue(movement.resolveGround(empty, new Point()).isEmpty());
    }

    @Test
    void absoluteMovePacketContainsVelocityAndFoothold() {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(123);
        when(bot.getStance()).thenReturn(4);

        Packet packet = movement.buildMovePacket(bot,
                new CompanionMovementService.GroundedPosition(new Point(20, 99), 77));
        ByteBuffer bytes = ByteBuffer.wrap(packet.getBytes()).order(ByteOrder.LITTLE_ENDIAN);

        bytes.getShort(); // opcode
        assertEquals(123, bytes.getInt());
        assertEquals(0, bytes.getInt());
        assertEquals(1, Byte.toUnsignedInt(bytes.get()));
        assertEquals(0, Byte.toUnsignedInt(bytes.get()));
        assertEquals(20, bytes.getShort());
        assertEquals(99, bytes.getShort());
        assertEquals(0, bytes.getShort());
        assertEquals(0, bytes.getShort());
        assertEquals(77, bytes.getShort());
        assertEquals(4, Byte.toUnsignedInt(bytes.get()));
        assertEquals(0, bytes.getShort());
        assertFalse(bytes.hasRemaining());
    }

    private static MapleMap mapWithFlatFoothold(int footholdId, int y) {
        MapleMap map = new MapleMap(100_020_000, 0, 1, 100_000_000, 1);
        FootholdTree tree = new FootholdTree(
                new Point(-1000, -1000), new Point(1000, 1000));
        tree.insert(new Foothold(new Point(-500, y), new Point(500, y), footholdId));
        map.setFootholds(tree);
        return map;
    }
}
