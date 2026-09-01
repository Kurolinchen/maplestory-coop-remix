/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import net.server.world.World;
import tools.PacketCreator;

/**
 * coop 0.1 operator helper. Aggregates the six world rate setters into one command
 * for less chat noise during playtests. Existing {@code !exprate}/{@code !mesorate}/etc
 * commands stay untouched for backwards compatibility.
 *
 * <p>Usage:
 * <pre>
 *   !rate              -&gt; list every world rate on one line
 *   !rate exp 10       -&gt; set exp rate
 *   !rate meso 5       -&gt; set meso rate
 *   !rate drop 3       -&gt; set common drop rate
 *   !rate boss 1       -&gt; set boss drop rate
 *   !rate quest 5      -&gt; set quest rate
 *   !rate travel 10    -&gt; set travel rate
 *   !rate fish 5       -&gt; set fishing rate
 * </pre>
 */
public class RateCommand extends Command {
    {
        setDescription("Show or set world rates: !rate [exp|meso|drop|boss|quest|travel|fish] [value]");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        World world = c.getWorldServer();
        if (params.length == 0) {
            player.yellowMessage("Rates  exp=" + world.getExpRate()
                    + " meso=" + world.getMesoRate()
                    + " drop=" + world.getDropRate()
                    + " boss=" + world.getBossDropRate()
                    + " quest=" + world.getQuestRate()
                    + " travel=" + world.getTravelRate()
                    + " fish=" + world.getFishingRate());
            return;
        }
        if (params.length < 2) {
            player.yellowMessage("Syntax: !rate [exp|meso|drop|boss|quest|travel|fish] [value]");
            return;
        }
        int value;
        try {
            value = Math.max(Integer.parseInt(params[1]), 1);
        } catch (NumberFormatException nfe) {
            player.yellowMessage("'" + params[1] + "' is not a valid rate.");
            return;
        }
        switch (params[0].toLowerCase()) {
            case "exp" -> {
                world.setExpRate(value);
                world.broadcastPacket(PacketCreator.serverNotice(6, "[Rate] Exp Rate has been changed to " + value + "x."));
            }
            case "meso" -> {
                world.setMesoRate(value);
                world.broadcastPacket(PacketCreator.serverNotice(6, "[Rate] Meso Rate has been changed to " + value + "x."));
            }
            case "drop" -> {
                world.setDropRate(value);
                world.broadcastPacket(PacketCreator.serverNotice(6, "[Rate] Drop Rate has been changed to " + value + "x."));
            }
            case "boss" -> {
                world.setBossDropRate(value);
                world.broadcastPacket(PacketCreator.serverNotice(6, "[Rate] Boss Drop Rate has been changed to " + value + "x."));
            }
            case "quest" -> {
                world.setQuestRate(value);
                world.broadcastPacket(PacketCreator.serverNotice(6, "[Rate] Quest Rate has been changed to " + value + "x."));
            }
            case "travel" -> {
                world.setTravelRate(value);
                world.broadcastPacket(PacketCreator.serverNotice(6, "[Rate] Travel Rate has been changed to " + value + "x."));
            }
            case "fish", "fishing" -> {
                world.setFishingRate(value);
                world.broadcastPacket(PacketCreator.serverNotice(6, "[Rate] Fishing Rate has been changed to " + value + "x."));
            }
            default -> player.yellowMessage("Unknown rate '" + params[0] + "'. Use one of: exp|meso|drop|boss|quest|travel|fish.");
        }
    }
}
