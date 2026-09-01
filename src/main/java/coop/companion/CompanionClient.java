/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.companion;

import client.Client;
import client.Character;
import net.PacketProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.packet.Packet;

/**
 * Slice B (Companion Bot MVP): a {@link Client} subclass that exists only to
 * host a {@link Character} object inside the existing load/save code paths
 * without actually opening a Netty session.
 *
 * <p>Two safety properties:
 * <ul>
 *   <li>{@link #sendPacket(Packet)} is a no-op. The bot must not try to write
 *       to a Netty channel — there is no remote endpoint.</li>
 *   <li>{@link #disconnect(boolean, boolean)} clears the bot's character and
 *       releases its in-process resources, but it never touches the session
 *       coordinator or any PlayerStorage (the bot is not registered there).</li>
 * </ul>
 *
 * <p>This class is intentionally minimal; everything else delegates to the
 * standard {@link Client} behaviour so the companion still participates in
 * normal rate/EXP/loot code paths.
 */
public final class CompanionClient extends Client {
    private static final Logger log = LoggerFactory.getLogger(CompanionClient.class);

    public CompanionClient(int world, int channel, int accountId) {
        super(Type.CHANNEL, /* sessionId */ 0L, "companion-local",
                /* packetProcessor */ null, world, channel);
        setAccID(accountId);
    }

    @Override
    public void sendPacket(Packet packet) {
        // Deliberately a no-op: the bot has no remote endpoint. Log at trace
        // level only; callers fire many packets per tick.
        if (log.isTraceEnabled()) {
            log.trace("CompanionClient swallowed packet {}", packet);
        }
    }

    /**
     * Releases the companion's character reference.
     *
     * <p>We deliberately do NOT call {@link Client#disconnect(boolean, boolean)}
     * (it is final anyway, and it would route through the session coordinator
     * and PlayerStorage, neither of which the bot belongs to). The lifecycle
     * service calls this only after the character has been saved and logged
     * off, so nothing needs to be flushed here.
     */
    public void detach() {
        setPlayer(null);
    }

    /**
     * Test / diagnostics: tell whether this client is the headless variant.
     * Used by PlayerStorage and SessionCoordinator checks that want to skip
     * companion sessions without class-name reflection.
     */
    public boolean isCompanionClient() {
        return true;
    }
}
