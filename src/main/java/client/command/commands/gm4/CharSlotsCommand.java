/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import coop.config.CoopDefaults;
import net.server.Server;
import tools.PacketCreator;

public class CharSlotsCommand extends Command {
    {
        setDescription("Set the account's character slot count.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        // coop 0.1: read-only mode reports the current state without mutating anything
        if (params.length < 1) {
            player.yellowMessage("Character slots: " + c.getCharacterSlots()
                    + " (cap " + CoopDefaults.maxCharacterSlots() + "). Syntax: !charslots <amount>");
            return;
        }

        int slots;
        try {
            slots = Integer.parseInt(params[0]);
        } catch (NumberFormatException nfe) {
            player.yellowMessage("Syntax: !charslots <amount>");
            return;
        }

        slots = CoopDefaults.clamp(slots, 1, 127);
        // never below the number of characters the account already owns
        int owned = Server.getInstance().getAccountCharacterCount(c.getAccID());
        if (slots < owned) {
            slots = owned;
        }
        c.setCharacterSlotsPersistent(slots);
        c.sendPacket(PacketCreator.showBoughtCharacterSlot((short) slots));
        player.yellowMessage("Character slots set to " + slots + ".");
    }
}
