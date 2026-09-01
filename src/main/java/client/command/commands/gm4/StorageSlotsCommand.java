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
import server.Storage;
import tools.PacketCreator;

public class StorageSlotsCommand extends Command {
    {
        setDescription("Raise the account storage slot count toward a target.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        // coop 0.1: read-only mode reports the current state without mutating anything
        if (params.length < 1) {
            int cap = CoopDefaults.storageSlotCap();
            int current = player.getStorage().getSlots();
            player.yellowMessage("Storage slots: " + current + " (cap " + cap + ", raising only). Syntax: !storageslots <target>");
            return;
        }

        int target;
        try {
            target = Integer.parseInt(params[0]);
        } catch (NumberFormatException nfe) {
            player.yellowMessage("Syntax: !storageslots <target>");
            return;
        }

        int cap = CoopDefaults.storageSlotCap();
        if (target > cap) {
            player.yellowMessage("Storage slot cap is " + cap + " (coop.storage_slot_cap).");
            target = cap;
        }

        Storage storage = player.getStorage();
        int delta = target - storage.getSlots();
        if (delta <= 0) {
            player.yellowMessage("Storage already has " + storage.getSlots() + " slots (raising only).");
            return;
        }

        if (storage.gainSlots(delta)) {
            player.setUsedStorage();
            c.sendPacket(PacketCreator.showBoughtStorageSlots(storage.getSlots()));
            player.yellowMessage("Storage slots set to " + storage.getSlots() + ".");
        } else {
            player.yellowMessage("Could not gain storage slots.");
        }
    }
}
