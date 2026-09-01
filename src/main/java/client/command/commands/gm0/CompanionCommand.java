/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import coop.companion.CompanionController;
import net.server.Server;

/**
 * Player-facing Companion Bot control (Slice B).
 *
 * <p>Syntax: {@code @companion <bind|unbind|spawn|dismiss|status> [name]}
 * <ul>
 *   <li>{@code bind <character-name>} — register an owned alt as this
 *       character's companion (same account + same world required).</li>
 *   <li>{@code unbind} — clear the binding (dismisses an active companion).</li>
 *   <li>{@code spawn} — bring the bound companion into the current map and
 *       party. Requires a party with a free slot and an allowlisted map.</li>
 *   <li>{@code dismiss} — save and remove the companion.</li>
 *   <li>{@code status} — show binding and live-session state.</li>
 * </ul>
 *
 * <p>All policy (ownership, limits, enablement) lives in
 * {@link CompanionController} so the disconnect/shutdown hooks share the exact
 * same rules.
 */
public class CompanionCommand extends Command {
    {
        setDescription("Manage your Companion: bind|unbind|spawn|dismiss|status [name]");
    }

    private final CompanionController controller = CompanionController.getInstance();

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length < 1) {
            player.yellowMessage("Syntax: @companion <bind|unbind|spawn|dismiss|status> [character-name]");
            return;
        }
        String sub = params[0].toLowerCase();
        switch (sub) {
            case "bind" -> {
                if (params.length < 2) {
                    player.yellowMessage("Syntax: @companion bind <character-name>");
                    return;
                }
                String targetName = String.join(" ", params).substring(params[0].length()).trim();
                Integer companionId = lookupOwnCharacterId(c, targetName);
                if (companionId == null) {
                    player.yellowMessage("No character named '" + targetName
                            + "' found on this account and world.");
                    return;
                }
                report(player, controller.bind(player, companionId));
            }
            case "unbind" -> report(player, controller.unbind(player));
            case "spawn" -> report(player, controller.spawn(player));
            case "dismiss" -> report(player, controller.dismiss(player));
            case "status" -> player.yellowMessage(controller.status(player));
            default -> player.yellowMessage("Unknown subcommand: " + params[0]
                    + ". Use bind|unbind|spawn|dismiss|status.");
        }
    }

    private void report(Character player, CompanionController.Outcome outcome) {
        player.yellowMessage(outcome.message());
    }

    /**
     * Resolves a character name to an id restricted to the caller's account and
     * world. Returns null when no such character exists, so the controller can
     * still run its own authoritative ownership check afterwards.
     */
    private Integer lookupOwnCharacterId(Client c, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try (java.sql.Connection con = tools.DatabaseConnection.getConnection();
             java.sql.PreparedStatement ps = con.prepareStatement(
                     "SELECT id FROM characters WHERE name = ? AND accountid = ? AND world = ?")) {
            ps.setString(1, name);
            ps.setInt(2, c.getAccID());
            ps.setInt(3, c.getWorld());
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("id");
                }
            }
        } catch (java.sql.SQLException e) {
            org.slf4j.LoggerFactory.getLogger(CompanionCommand.class)
                    .error("Companion bind lookup failed for name '{}'", name, e);
        }
        return null;
    }
}
