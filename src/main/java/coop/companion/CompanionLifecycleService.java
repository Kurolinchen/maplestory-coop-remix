/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.companion;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.PartyOperation;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Slice B (Companion Bot MVP): orchestrates the lifecycle of a companion
 * character between "loaded on a map in the owner's party" and "saved and
 * completely detached".
 *
 * <p>The companion is intentionally NOT registered in
 * {@code Channel}/{@code World} {@code PlayerStorage}; it is only attached to
 * the current {@link MapleMap} and to the owner's real {@link Party}. That
 * keeps channel capacity, login reuse, buddy presence and disconnect paths
 * untouched while still letting kill/EXP/loot code paths see a real
 * {@link Character}.
 *
 * <p>Every transition is guarded by testing the preconditions first and
 * rolling back partial attach operations on failure, so a failed spawn can
 * never leave a half-attached character behind.
 */
public final class CompanionLifecycleService {
    private static final Logger log = LoggerFactory.getLogger(CompanionLifecycleService.class);

    private static final CompanionLifecycleService INSTANCE = new CompanionLifecycleService();

    public static CompanionLifecycleService getInstance() {
        return INSTANCE;
    }

    private CompanionLifecycleService() {
    }

    /** Result of an attach/detach attempt; carries a human-readable reason on failure. */
    public record Result(boolean success, String reason, Character companion) {
        public static Result ok(Character chr) {
            return new Result(true, null, chr);
        }
        public static Result fail(String reason) {
            return new Result(false, reason, null);
        }
    }

    /**
     * Loads the companion character, attaches it to the given map, and joins it
     * into the owner's party. Returns the loaded character on success.
     *
     * <p>The caller MUST have already reserved the slot through
     * {@code CompanionManager.register(session)} and MUST hold no locks that
     * the attach path needs.
     */
    public Result spawn(Character owner, CompanionSession session) {
        if (owner == null || session == null) {
            return Result.fail("owner or session missing");
        }
        MapleMap map = owner.getMap();
        if (map == null) {
            return Result.fail("owner has no map");
        }
        Party party = owner.getParty();
        if (party == null) {
            return Result.fail("owner is not in a party");
        }
        if (party.getMembers().size() >= 6) {
            return Result.fail("owner's party is full (cap 6)");
        }

        CompanionClient client = new CompanionClient(session.world(), session.channel(),
                session.accountId());
        Character bot;
        try {
            bot = Character.loadCharFromDB(session.companionCharacterId(), client, true);
        } catch (SQLException | RuntimeException e) {
            log.error("Companion load failed owner={} companion={}", session.ownerCharacterId(),
                    session.companionCharacterId(), e);
            return Result.fail("companion load failed: " + e.getMessage());
        }
        if (bot == null) {
            return Result.fail("companion character could not be loaded");
        }

        client.setPlayer(bot);
        try {
            bot.setWorld(session.world());
            // Mirrors the rate initialisation PlayerLoggedinHandler performs.
            bot.setPlayerRates();
        } catch (RuntimeException e) {
            log.warn("Companion rate initialisation failed owner={} companion={}: {}",
                    session.ownerCharacterId(), session.companionCharacterId(), e.getMessage());
        }

        // 1) Attach to the map first so PartyCharacter can read a valid map id.
        try {
            map.addPlayer(bot);
        } catch (RuntimeException e) {
            log.error("Companion map attach failed owner={} companion={}", session.ownerCharacterId(),
                    session.companionCharacterId(), e);
            safeLogoff(bot, client);
            return Result.fail("map attach failed: " + e.getMessage());
        }

        // 2) Join the owner's real party through the authoritative path.
        try {
            PartyCharacter mpc = new PartyCharacter(bot);
            World wserv = owner.getWorldServer();
            wserv.updateParty(party.getId(), PartyOperation.JOIN, mpc);
            bot.setMPC(mpc);
            bot.setParty(party);
        } catch (RuntimeException e) {
            log.error("Companion party join failed owner={} companion={}", session.ownerCharacterId(),
                    session.companionCharacterId(), e);
            // Roll the map attach back before giving up.
            try {
                map.removePlayer(bot);
            } catch (RuntimeException inner) {
                log.warn("Rollback of companion map attach failed: {}", inner.getMessage());
            }
            safeLogoff(bot, client);
            return Result.fail("party join failed: " + e.getMessage());
        }

        session.compareAndSetState(CompanionSession.State.NEW, CompanionSession.State.ACTIVE);
        log.info("Companion spawned: owner={} companion={} map={} party={}",
                session.ownerCharacterId(), session.companionCharacterId(),
                map.getId(), party.getId());
        return Result.ok(bot);
    }

    /**
     * Detaches the companion from its party and map, saves it synchronously and
     * releases the client. Never throws: failures are logged and returned so the
     * caller can escalate (e.g. mark SAVE_FAILED and keep the session).
     */
    public Result dismiss(CompanionSession session, Character bot) {
        if (session == null) {
            return Result.fail("session missing");
        }
        session.compareAndSetState(CompanionSession.State.ACTIVE,
                CompanionSession.State.DISMISSING);

        CompanionClient client = bot != null && bot.getClient() instanceof CompanionClient cc
                ? cc : null;

        if (bot != null) {
            // 1) leave the party through the authoritative path
            try {
                Party party = bot.getParty();
                if (party != null) {
                    World wserv = bot.getWorldServer();
                    PartyCharacter mpc = bot.getMPC();
                    if (mpc != null) {
                        wserv.updateParty(party.getId(), PartyOperation.LEAVE, mpc);
                    }
                    bot.setParty(null);
                    bot.setMPC(null);
                }
            } catch (RuntimeException e) {
                log.warn("Companion party leave failed companion={}: {}",
                        session.companionCharacterId(), e.getMessage());
            }

            // 2) leave the map
            try {
                MapleMap map = bot.getMap();
                if (map != null) {
                    map.removePlayer(bot);
                }
            } catch (RuntimeException e) {
                log.warn("Companion map detach failed companion={}: {}",
                        session.companionCharacterId(), e.getMessage());
            }

            // 3) synchronous save (NOT the autosave variant: the object is about
            //    to be discarded and a queued save could be lost)
            try {
                bot.saveCharToDB(true);
                bot.logOff();
                session.markSaveCompleted();
            } catch (RuntimeException e) {
                log.error("Companion save failed companion={}; holding session in SAVE_FAILED",
                        session.companionCharacterId(), e);
                session.compareAndSetState(CompanionSession.State.DISMISSING,
                        CompanionSession.State.SAVE_FAILED);
                return Result.fail("save failed: " + e.getMessage());
            }
        }

        if (client != null) {
            client.detach();
        }
        session.compareAndSetState(CompanionSession.State.DISMISSING,
                CompanionSession.State.CLOSED);
        log.info("Companion dismissed: owner={} companion={}", session.ownerCharacterId(),
                session.companionCharacterId());
        return Result.ok(null);
    }

    private void safeLogoff(Character bot, CompanionClient client) {
        try {
            bot.logOff();
        } catch (RuntimeException e) {
            log.warn("Companion logOff after failed attach failed: {}", e.getMessage());
        }
        try {
            client.detach();
        } catch (RuntimeException e) {
            log.warn("Companion client disconnect after failed attach failed: {}", e.getMessage());
        }
    }

    /**
     * Persists the companion's binding mode so a later spawn resumes in the
     * same behaviour. Pure data update; callers should not depend on ordering.
     */
    public boolean persistMode(int ownerCharacterId, CompanionSession.Mode mode,
                              boolean lootEnabled) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "UPDATE coop_companion_bindings SET mode = ?, loot_enabled = ? "
                             + "WHERE owner_character_id = ?")) {
            ps.setString(1, mode.name());
            ps.setBoolean(2, lootEnabled);
            ps.setInt(3, ownerCharacterId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            log.error("Failed to persist companion mode for owner {}", ownerCharacterId, e);
            return false;
        }
    }
}
