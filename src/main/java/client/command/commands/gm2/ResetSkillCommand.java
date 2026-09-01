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
package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.command.Command;
import coop.reset.SkillResetService;

public class ResetSkillCommand extends Command {
    {
        setDescription("Reset all learned skills and refund spent SP.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        // coop 0.1: resets learned skills and refunds spent SP per skillbook (was: zero all skills without refund)
        SkillResetService.resetSkills(player, true);
        player.yellowMessage("Skills reset; spent SP refunded.");
    }
}
