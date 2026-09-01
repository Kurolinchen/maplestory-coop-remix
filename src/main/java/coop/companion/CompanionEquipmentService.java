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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Slice E (Companion equipment): owner-directed equip through the normal wear
 * validation path.
 *
 * <p>The MVP deliberately has NO automatic "best item" optimiser. Deciding
 * upgrades automatically requires comparing two-handed vs offhand, rings, cash
 * slots, class and level requirements and stat trade-offs — doing that badly
 * would silently destroy scrolled gear. Until that logic exists and is tested,
 * the owner equips the companion explicitly and every change goes through
 * {@code InventoryManipulator.equip}, which performs the same validation a real
 * player's client request would.
 */
public final class CompanionEquipmentService {
    private static final Logger log = LoggerFactory.getLogger(CompanionEquipmentService.class);

    public record EquipResult(boolean success, String reason) {
        public static EquipResult ok() {
            return new EquipResult(true, null);
        }
        public static EquipResult fail(String reason) {
            return new EquipResult(false, reason);
        }
    }

    /**
     * Equips the item in the companion's EQUIP inventory slot {@code sourceSlot}
     * into {@code targetSlot}. Both slots use the inventory's own indexing.
     */
    public EquipResult equip(Character bot, int sourceSlot, short targetSlot) {
        if (bot == null) {
            return EquipResult.fail("companion is not available");
        }
        if (!bot.isAlive()) {
            return EquipResult.fail("companion is dead");
        }
        Item item = bot.getInventory(InventoryType.EQUIP).getItem((byte) sourceSlot);
        if (item == null) {
            return EquipResult.fail("no item in equip slot " + sourceSlot);
        }
        try {
            InventoryManipulator.equip(bot.getClient(), (byte) sourceSlot, (byte) targetSlot);
        } catch (RuntimeException e) {
            log.warn("Companion equip failed companion={} slot={} -> {}: {}",
                    bot.getId(), sourceSlot, targetSlot, e.getMessage());
            return EquipResult.fail("equip rejected: " + e.getMessage());
        }
        log.info("Owner equipped companion {} slot {} -> {}", bot.getId(), sourceSlot, targetSlot);
        return EquipResult.ok();
    }

    /** Unequips the item currently in {@code sourceSlot} back into the inventory. */
    public EquipResult unequip(Character bot, int sourceSlot) {
        if (bot == null) {
            return EquipResult.fail("companion is not available");
        }
        short freeSlot = findFreeEquipInventorySlot(bot);
        if (freeSlot < 0) {
            return EquipResult.fail("no free slot in the companion's equip inventory");
        }
        try {
            InventoryManipulator.unequip(bot.getClient(), (byte) sourceSlot, freeSlot);
        } catch (RuntimeException e) {
            log.warn("Companion unequip failed companion={} slot={}: {}",
                    bot.getId(), sourceSlot, e.getMessage());
            return EquipResult.fail("unequip rejected: " + e.getMessage());
        }
        log.info("Owner unequipped companion {} slot {}", bot.getId(), sourceSlot);
        return EquipResult.ok();
    }

    /** First empty slot in the EQUIP inventory, or -1 when it is full. */
    private short findFreeEquipInventorySlot(Character bot) {
        client.inventory.Inventory equipInventory = bot.getInventory(InventoryType.EQUIP);
        for (short slot = 1; slot <= equipInventory.getSlotLimit(); slot++) {
            if (equipInventory.getItem((byte) slot) == null) {
                return slot;
            }
        }
        return -1;
    }
}
