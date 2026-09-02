/*
    This file is part of the HeavenMS MapleStory Server, commands OdinMS-based
    Copyleft (L) 2016 - 2019 RonanLana

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

/*
   @Author: Arthur L - Refactored command content into modules
*/
package client.command.commands.gm6;

import client.Character;
import client.Client;
import client.command.Command;
import coop.security.GmLevel;
import coop.security.GmLevelPersistence;

public class SetGmLevelCommand extends Command {
    {
        setDescription("Set GM level of a player.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length < 2) {
            player.yellowMessage("Syntax: !setgmlevel <playername> <newlevel>");
            return;
        }

        int newLevel;
        try {
            newLevel = Integer.parseInt(params[1]);
        } catch (NumberFormatException e) {
            player.yellowMessage("GM level must be a number from 0 to 6.");
            return;
        }
        if (!GmLevel.isValid(newLevel)) {
            player.yellowMessage("GM level must be from 0 to 6.");
            return;
        }

        Character target = c.getChannelServer().getPlayerStorage().getCharacterByName(params[0]);
        if (target != null) {
            synchronized (target) {
                if (!GmLevelPersistence.updateCharacter(target.getId(), newLevel)) {
                    player.dropMessage("Could not persist the GM level. No change was applied.");
                    return;
                }
                target.setGMLevel(newLevel);
                target.getClient().setGMLevel(target.gmLevel());
            }

            target.dropMessage("You are now a level " + newLevel + " GM. See @commands for a list of available commands.");
            player.dropMessage(target + " is now a level " + newLevel + " GM.");
        } else {
            player.dropMessage("Player '" + params[0] + "' was not found on this channel.");
        }
    }
}
