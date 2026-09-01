/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import client.inventory.InventoryType;
import coop.config.CoopDefaults;
import net.server.Server;

public class CharInfoCommand extends Command {
    {
        // coop 0.1: operators want a one-shot read of the Milestone 0.1 contract
        // values for the current character without running any SQL.
        setDescription("Show this character's Milestone 0.1 capacity and resource stats.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        int owned = Server.getInstance().getAccountCharacterCount(c.getAccID());

        int equipSlots = player.getInventory(InventoryType.EQUIP).getSlotLimit();
        int useSlots = player.getInventory(InventoryType.USE).getSlotLimit();
        int setupSlots = player.getInventory(InventoryType.SETUP).getSlotLimit();
        int etcSlots = player.getInventory(InventoryType.ETC).getSlotLimit();
        int storageSlots = player.getStorage().getSlots();
        int buddyCap = player.getBuddylist().getCapacity();

        String line = "Char " + player.getName() + " (lv " + player.getLevel() + ", job " + player.getJob()
                + ", gm " + player.gmLevel() + ")"
                + "  Char slots: " + owned + "/" + c.getCharacterSlots()
                + " (cap " + CoopDefaults.maxCharacterSlots() + ")"
                + "  Inv: equip=" + equipSlots + " use=" + useSlots + " setup=" + setupSlots + " etc=" + etcSlots
                + "  Storage: " + storageSlots + "/" + CoopDefaults.storageSlotCap()
                + "  Buddies cap=" + buddyCap
                + "  AP=" + player.getRemainingAp() + " SP=" + player.getRemainingSp()
                + "  Meso=" + player.getMeso()
                + "  Map=" + player.getMapId();
        player.yellowMessage(line);
    }
}
