/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.companion;

import client.Character;
import client.Stat;
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
        // Serialise against a concurrent dismiss coming from the command thread,
        // the owner-disconnect hook or the tick loop. Without this, spawn and
        // dismiss can interleave and leave a half-attached character.
        session.lock().lock();
        try {
            return spawnLocked(owner, session);
        } finally {
            session.lock().unlock();
        }
    }

    private Result spawnLocked(Character owner, CompanionSession session) {
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
        // Only one thread may actually perform the dismissal. The CAS alone is
        // not enough: three call paths (command, owner disconnect, tick) can
        // arrive concurrently, and a second pass would repeat the party leave
        // and the save while the first is still running.
        session.lock().lock();
        try {
            if (session.state() == CompanionSession.State.CLOSED) {
                return Result.ok(null); // already dismissed; idempotent
            }
            if (session.state() == CompanionSession.State.SAVE_FAILED) {
                return Result.fail("a previous save failed; state is being held for retry");
            }
            if (!session.compareAndSetState(CompanionSession.State.ACTIVE,
                    CompanionSession.State.DISMISSING)) {
                return Result.fail("companion is not in a dismissable state: " + session.state());
            }
            return dismissLocked(session, bot);
        } finally {
            session.lock().unlock();
        }
    }

    private Result dismissLocked(CompanionSession session, Character bot) {
        // An unresolvable companion must NOT be reported as a successful
        // dismissal: nothing would be saved, the loaded Character would stay
        // attached to its map and party, and the freed slot would let a later
        // spawn load the SAME database row a second time (two savers for one
        // row = lost or duplicated items/meso/EXP). Hold the session instead.
        if (bot == null) {
            session.compareAndSetState(CompanionSession.State.DISMISSING,
                    CompanionSession.State.SAVE_FAILED);
            return Result.fail("companion object is unreachable; state held for retry");
        }

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

            // 3) a dead companion must not be persisted at 0 HP. Saving hp=0
            //    bricks the character: the next spawn would immediately see
            //    "not alive", dismiss again, and loop forever until someone
            //    logs into the alt and heals it by hand. Restore to a safe
            //    fraction of the pools first, exactly like a normal respawn.
            if (bot.getHp() <= 0) {
                try {
                    // addHP/addMP are the public mutators; both clamp to the
                    // character's max pools, so a single large heal is safe.
                    bot.addHP(bot.getMaxHp());
                    bot.addMP(bot.getMaxMp());
                } catch (RuntimeException e) {
                    log.warn("Could not restore companion HP/MP before save companion={}: {}",
                            session.companionCharacterId(), e.getMessage());
                }
            }

            // 4) synchronous save (NOT the autosave variant: the object is about
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
     * Moves an already-attached companion from one map to another without
     * running any portal script. Used by the portal-follow path (Slice C).
     *
     * <p>The transfer is a plain detach/attach pair: the party membership is
     * untouched, so the companion stays in the owner's party across the hop.
     */
    public Result transferToMap(Character bot, MapleMap source, MapleMap destination) {
        if (bot == null || source == null || destination == null) {
            return Result.fail("transfer requires bot, source and destination");
        }
        if (source.getId() == destination.getId()) {
            return Result.fail("source and destination are the same map");
        }
        try {
            source.removePlayer(bot);
        } catch (RuntimeException e) {
            log.error("Companion transfer failed at source detach companion={}: {}",
                    bot.getId(), e.getMessage());
            return Result.fail("source detach failed: " + e.getMessage());
        }
        try {
            destination.addPlayer(bot);
        } catch (RuntimeException e) {
            log.error("Companion transfer failed at destination attach companion={}: {}",
                    bot.getId(), e.getMessage());
            // Put the bot back where it was so we never lose it entirely.
            try {
                source.addPlayer(bot);
            } catch (RuntimeException inner) {
                log.error("Companion rollback to source map failed companion={}: {}",
                        bot.getId(), inner.getMessage());
            }
            return Result.fail("destination attach failed: " + e.getMessage());
        }
        return Result.ok(bot);
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
