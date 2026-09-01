/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import coop.config.CoopDefaults;
import coop.earlygame.TrainingSpots;
import server.life.MonsterInformationProvider;
import server.maps.MapFactory;

import java.util.List;

/**
 * Early Game Remix (coop 0.1b): {@code @training} — a player-facing training
 * guide.
 *
 * <p>Player command (rank 0). It only REPORTS suitable training spots; it never
 * teleports, so it cannot be used to skip travel or content. Enabled through
 * {@code coop.early_game.training_guide_enabled}.
 *
 * <p>All policy lives in {@link TrainingSpots}; this class is a thin shell that
 * formats the answer, matching the {@code CompanionCommand} convention.
 */
public class TrainingCommand extends Command {
    {
        setDescription("Show level-appropriate training spots.");
    }

    @Override
    public void execute(Client client, String[] params) {
        Character player = client.getPlayer();
        if (!CoopDefaults.trainingGuideEnabled()) {
            player.yellowMessage("The training guide is currently disabled.");
            return;
        }

        int level = player.getLevel();
        if (params.length >= 1) {
            try {
                level = Integer.parseInt(params[0]);
            } catch (NumberFormatException e) {
                player.yellowMessage("Syntax: @training [level]");
                return;
            }
        }
        if (level < 1 || level > 200) {
            player.yellowMessage("Level must be between 1 and 200.");
            return;
        }

        TrainingSpots trainingSpots = TrainingSpots.getInstance();
        List<TrainingSpots.Spot> spots = trainingSpots.spotsForLevel(
                level, CoopDefaults.trainingLevelDelta(), CoopDefaults.trainingMaxResults());
        if (spots.isEmpty()) {
            if (!trainingSpots.isReady()) {
                // The first query triggers an asynchronous index build; it must
                // never block the packet thread, so ask the player to retry.
                player.yellowMessage("The training index is still being built. Try again in a few seconds.");
                return;
            }
            player.yellowMessage("No training spot was found near level " + level + ".");
            return;
        }

        StringBuilder msg = new StringBuilder();
        msg.append("#eTraining spots for level ").append(level).append("#n\r\n");
        for (TrainingSpots.Spot spot : spots) {
            String place = MapFactory.loadPlaceName(spot.mapId());
            String street = MapFactory.loadStreetName(spot.mapId());
            String area = street.isEmpty() ? place : street + " - " + place;
            if (area == null || area.trim().isEmpty()) {
                area = "Map " + spot.mapId();
            }
            String mobName = MonsterInformationProvider.getInstance()
                    .getMobNameFromId(spot.mobId());
            if (mobName == null || mobName.isEmpty()) {
                mobName = "Monster " + spot.mobId();
            }
            msg.append("#b").append(area).append("#k  (map ").append(spot.mapId())
                    .append(", ").append(mobName).append(" lv.").append(spot.mobLevel())
                    .append(")\r\n");
        }
        msg.append("\r\nWalk there yourself - this command does not teleport.");
        player.showHint(msg.toString(), 300);
    }
}
