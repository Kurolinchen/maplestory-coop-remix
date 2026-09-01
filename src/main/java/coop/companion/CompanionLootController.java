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
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.MapleMap;

/**
 * Slice E (Companion looting): opt-in pickup of nearby legal loot.
 *
 * <p>Integrity rules, all enforced by delegating rather than reimplementing:
 * <ul>
 *   <li>pickup goes through {@code Character.pickupItem}, the same path a real
 *       player uses, so the 400ms delay, ownership rules, inventory-capacity
 *       check, scripted-item handling, NX handling and meso splitting all keep
 *       their normal semantics;</li>
 *   <li>ownership is not second-guessed here: an item the companion may not
 *       have is skipped silently;</li>
 *   <li>looted items land in the COMPANION's inventory, never the owner's, and
 *       are never transferred afterwards — a companion is a normal character,
 *       not a bag of holding;</li>
 *   <li>looting is disabled unless the session explicitly enables it, because a
 *       bot vacuuming party loot is the fastest way to make group play worse.</li>
 * </ul>
 */
public final class CompanionLootController {
    private static final Logger log = LoggerFactory.getLogger(CompanionLootController.class);

    private long lastLootAt = 0L;

    public record LootResult(int picked, int skipped, String reason) {
        public static LootResult none(String reason) {
            return new LootResult(0, 0, reason);
        }
        public static LootResult done(int picked, int skipped) {
            return new LootResult(picked, skipped, null);
        }
    }

    /** Attempts to pick up nearby eligible drops. At most one pass per call. */
    public LootResult tick(CompanionSession session, Character bot) {
        if (session == null || bot == null) {
            return LootResult.none("missing session/bot");
        }
        if (!session.lootEnabled()) {
            return LootResult.none("looting disabled for this companion");
        }
        if (!bot.isAlive()) {
            return LootResult.none("companion is dead");
        }
        MapleMap map = bot.getMap();
        if (map == null) {
            return LootResult.none("companion has no map");
        }
        long now = System.currentTimeMillis();
        if (now - lastLootAt < CoopDefaults.companionLootIntervalMs()) {
            return LootResult.none("loot cooldown");
        }
        lastLootAt = now;

        int radiusSq = CoopDefaults.companionLootRadius() * CoopDefaults.companionLootRadius();
        int picked = 0;
        int skipped = 0;
        for (MapObject obj : map.getMapObjects()) {
            if (!(obj instanceof MapItem item)) {
                continue;
            }
            if (item.isPickedUp()) {
                continue;
            }
            if (item.getPosition() == null
                    || item.getPosition().distanceSq(bot.getPosition()) > radiusSq) {
                continue;
            }
            if (!item.canBePickedBy(bot)) {
                // Ownership/FFA rules say no: leave it for whoever it belongs to.
                skipped++;
                continue;
            }
            bot.pickupItem(obj);
            picked++;
            // One item per pass keeps the tick bounded and the behaviour legible.
            break;
        }
        if (picked > 0) {
            log.debug("Companion {} picked up an item (skipped {})", bot.getId(), skipped);
        }
        return LootResult.done(picked, skipped);
    }
}
