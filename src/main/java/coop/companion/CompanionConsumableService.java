/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.companion;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import coop.config.CoopDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Slice D (Companion consumables): HP/MP potion use from the companion's OWN
 * inventory.
 *
 * <p>Integrity: exactly one item is removed through the normal
 * {@code InventoryManipulator} path and the item's real effect is applied. No
 * potion is ever created, and an empty inventory simply means the companion
 * stops attacking until it is resupplied.
 */
public final class CompanionConsumableService {
    private static final Logger log = LoggerFactory.getLogger(CompanionConsumableService.class);

    public enum Need { NONE, HP, MP }

    public record ConsumeResult(boolean consumed, String reason, int itemId) {
        public static ConsumeResult skipped(String reason) {
            return new ConsumeResult(false, reason, 0);
        }
        public static ConsumeResult used(int itemId) {
            return new ConsumeResult(true, null, itemId);
        }
    }

    /** Determines what the companion needs right now, if anything. */
    public Need evaluate(Character bot) {
        if (bot == null || !bot.isAlive()) {
            return Need.NONE;
        }
        int maxHp = bot.getMaxHp();
        if (maxHp > 0 && bot.getHp() <= maxHp * CoopDefaults.companionHpPotionRatio()) {
            return Need.HP;
        }
        int maxMp = bot.getMaxMp();
        if (maxMp > 0 && bot.getMp() <= maxMp * CoopDefaults.companionMpPotionRatio()) {
            return Need.MP;
        }
        return Need.NONE;
    }

    /** Consumes one appropriate potion. Returns why it did nothing when it did not. */
    public ConsumeResult consume(Character bot, Need need) {
        if (bot == null || need == Need.NONE) {
            return ConsumeResult.skipped("nothing needed");
        }
        List<Integer> allowed = need == Need.HP
                ? CoopDefaults.companionAllowedHpPotions()
                : CoopDefaults.companionAllowedMpPotions();

        Item potion = findPotion(bot, allowed, need);
        if (potion == null) {
            return ConsumeResult.skipped("no usable "
                    + (need == Need.HP ? "HP" : "MP") + " potion in inventory");
        }
        int itemId = potion.getItemId();

        // Apply the item's OWN effect FIRST. If the effect cannot be applied we
        // must not destroy the item: an earlier revision removed the potion and
        // only logged, which burned the companion's entire stack once per tick
        // without ever healing it.
        server.StatEffect effect =
                server.ItemInformationProvider.getInstance().getItemEffect(itemId);
        if (effect == null) {
            return ConsumeResult.skipped("potion " + itemId + " has no usable effect");
        }
        // Restrict to plain recovery effects.
        if (effect.getHp() <= 0 && effect.getMp() <= 0) {
            return ConsumeResult.skipped(
                    "item " + itemId + " is not a recovery item; refusing");
        }
        // Some consumables carry a `moveTo`, and honouring it would teleport the
        // companion away from the owner (StatEffect.applyTo -> changeMap). The
        // field is private with no getter, so we detect the effect after the
        // fact and undo it rather than trying to predict it.
        int mapBefore = bot.getMapId();
        if (!effect.applyTo(bot)) {
            return ConsumeResult.skipped("potion " + itemId + " effect was rejected");
        }
        if (bot.getMapId() != mapBefore) {
            log.warn("Companion {} was moved by item {} ({} -> {}); undoing",
                    bot.getId(), itemId, mapBefore, bot.getMapId());
            try {
                bot.changeMap(mapBefore);
            } catch (RuntimeException e) {
                log.error("Could not undo companion move after item {}; companion is on map {}",
                        itemId, bot.getMapId(), e);
            }
            return ConsumeResult.skipped(
                    "potion " + itemId + " tried to move the companion; effect reverted");
        }

        // Only now remove exactly one from the stack, through the standard
        // manipulator, so the item and its effect stay in lockstep.
        InventoryManipulator.removeFromSlot(bot.getClient(), InventoryType.USE,
                (byte) potion.getPosition(), (short) 1, false);
        log.debug("Companion {} used potion {}", bot.getId(), itemId);
        return ConsumeResult.used(itemId);
    }

    /**
     * Finds a potion that actually treats the given need.
     *
     * <p>The selection must distinguish HP from MP: picking the first recovery
     * item would let a companion at low HP drink a pure-MP potion (and vice
     * versa), burning the wrong stack while the real problem goes untreated.
     */
    private Item findPotion(Character bot, List<Integer> allowed, Need need) {
        boolean restricted = allowed != null && !allowed.isEmpty();
        for (Item item : bot.getInventory(InventoryType.USE).list()) {
            if (item == null || item.getQuantity() <= 0) {
                continue;
            }
            int itemId = item.getItemId();
            if (restricted) {
                if (allowed.contains(itemId)) {
                    return item;
                }
                continue;
            }
            if (!isUsablePotion(itemId)) {
                continue;
            }
            server.StatEffect effect =
                    server.ItemInformationProvider.getInstance().getItemEffect(itemId);
            if (effect == null) {
                continue;
            }
            boolean heals = need == Need.HP ? effect.getHp() > 0 : effect.getMp() > 0;
            if (heals) {
                return item;
            }
        }
        return null;
    }

    /**
     * Broad potion family check used when no explicit allowlist is configured.
     * Item ids 200xxxx are the standard HP/MP recovery consumables; 202xxxx
     * covers the larger "elixir"-style recovery items.
     */
    private boolean isUsablePotion(int itemId) {
        int prefix = itemId / 10000;
        return prefix == 200 || prefix == 202;
    }
}
