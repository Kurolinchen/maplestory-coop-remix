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

import java.awt.Point;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;

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
    private final CompanionMovementService movement = new CompanionMovementService();

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
        if (bot.getParty() != null) {
            safeLogoff(bot, client);
            return Result.fail("companion is still a member of another party");
        }
        try {
            bot.setWorld(session.world());
            // Mirrors the rate initialisation PlayerLoggedinHandler performs.
            bot.setPlayerRates();
        } catch (RuntimeException e) {
            log.warn("Companion rate initialisation failed owner={} companion={}: {}",
                    session.ownerCharacterId(), session.companionCharacterId(), e.getMessage());
        }

        Optional<CompanionMovementService.GroundedPosition> ground =
                movement.resolveGround(map, owner.getPosition());
        if (ground.isEmpty()) {
            safeLogoff(bot, client);
            return Result.fail("no valid foothold below the owner");
        }

        bot.setMap(map);
        bot.setPosition(ground.get().position());

        boolean mapAttached = false;
        boolean partyJoinAttempted = false;
        boolean mapPartyRegistered = false;
        PartyCharacter mpc = null;
        World wserv = owner.getWorldServer();
        try {
            map.addPlayer(bot);
            mapAttached = true;
            // Normal clients settle the entering-field y-42 offset with their
            // first movement packet. Headless companions need that correction
            // immediately, even in PASSIVE mode.
            movement.moveAndBroadcast(map, bot, ground.get());

            mpc = new PartyCharacter(bot);
            bot.setMPC(mpc);
            bot.setParty(party);
            partyJoinAttempted = true;
            wserv.updateParty(party.getId(), PartyOperation.JOIN, mpc);
            mapPartyRegistered = true;
            map.addPartyMember(bot, party.getId());
            synchronizePartyHp(bot);

            session.setCompanion(bot);
            if (!session.compareAndSetState(
                    CompanionSession.State.NEW, CompanionSession.State.ACTIVE)) {
                throw new IllegalStateException("session changed state during spawn");
            }
        } catch (RuntimeException e) {
            log.error("Companion attach failed owner={} companion={}", session.ownerCharacterId(),
                    session.companionCharacterId(), e);
            boolean clean = rollbackSpawn(bot, map, party, mpc, wserv, mapAttached,
                    partyJoinAttempted, mapPartyRegistered);
            if (!clean) {
                session.setCompanion(bot);
                session.markFailedSpawnRecovery();
                session.compareAndSetState(
                        CompanionSession.State.NEW, CompanionSession.State.SAVE_FAILED);
                return new Result(false,
                        "attach failed and cleanup is incomplete: " + e.getMessage(), bot);
            }
            session.setCompanion(null);
            safeLogoff(bot, client);
            return Result.fail("attach failed: " + e.getMessage());
        }

        log.info("Companion spawned: owner={} companion={} map={} party={}",
                session.ownerCharacterId(), session.companionCharacterId(),
                map.getId(), party.getId());
        return Result.ok(bot);
    }

    static void synchronizePartyHp(Character bot) {
        bot.receivePartyMemberHP();
        bot.updatePartyMemberHP();
    }

    private boolean rollbackSpawn(Character bot, MapleMap map, Party party,
                                  PartyCharacter mpc, World world,
                                  boolean mapAttached, boolean partyJoinAttempted,
                                  boolean mapPartyRegistered) {
        boolean clean = true;
        if (partyJoinAttempted && mpc != null) {
            try {
                Party authoritative = world.getParty(party.getId());
                if (authoritative != null && authoritative.getMemberById(bot.getId()) != null) {
                    world.updateParty(party.getId(), PartyOperation.LEAVE, mpc);
                }
            } catch (RuntimeException e) {
                log.warn("Rollback of companion world-party join failed: {}", e.getMessage());
                clean = false;
            }
        }
        if (clean && mapPartyRegistered) {
            try {
                map.removePartyMember(bot, party.getId());
            } catch (RuntimeException e) {
                log.warn("Rollback of companion map-party registration failed: {}", e.getMessage());
                clean = false;
            }
        }
        if (world.getParty(party.getId()) == null || party.getMemberById(bot.getId()) == null) {
            bot.setParty(null);
            bot.setMPC(null);
        } else {
            clean = false;
        }
        try {
            if (mapAttached || isAttached(map, bot)) {
                map.removePlayer(bot);
            }
        } catch (RuntimeException e) {
            log.warn("Rollback of companion map attach failed: {}", e.getMessage());
            clean = false;
        }
        return clean && !isAttached(map, bot);
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
            boolean dismissing = session.compareAndSetState(
                    CompanionSession.State.ACTIVE, CompanionSession.State.DISMISSING);
            if (!dismissing) {
                dismissing = session.compareAndSetState(
                        CompanionSession.State.SAVE_FAILED, CompanionSession.State.DISMISSING);
            }
            if (!dismissing) {
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
            String detachFailure = detachForSave(bot);
            if (detachFailure != null) {
                session.compareAndSetState(CompanionSession.State.DISMISSING,
                        CompanionSession.State.SAVE_FAILED);
                return Result.fail(detachFailure);
            }

            if (session.discardOnRecovery()) {
                bot.logOff();
                if (client != null) {
                    client.detach();
                }
                session.setCompanion(null);
                session.compareAndSetState(CompanionSession.State.DISMISSING,
                        CompanionSession.State.CLOSED);
                return Result.ok(null);
            }

            // A dead companion must not be persisted at 0 HP. Saving hp=0
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

            // Synchronous save (NOT the autosave variant: the object is about
            //    to be discarded and a queued save could be lost)
            if (!bot.saveCharToDBChecked(true)) {
                log.error("Companion save failed companion={}; holding session in SAVE_FAILED",
                        session.companionCharacterId());
                session.compareAndSetState(CompanionSession.State.DISMISSING,
                        CompanionSession.State.SAVE_FAILED);
                return Result.fail("save transaction failed; state held for retry");
            }
            bot.logOff();
            session.markSaveCompleted();
        }

        if (client != null) {
            client.detach();
        }
        session.setCompanion(null);
        session.compareAndSetState(CompanionSession.State.DISMISSING,
                CompanionSession.State.CLOSED);
        log.info("Companion dismissed: owner={} companion={}", session.ownerCharacterId(),
                session.companionCharacterId());
        return Result.ok(null);
    }

    private String detachForSave(Character bot) {
        Party party = bot.getParty();
        MapleMap map = bot.getMap();
        if (party != null) {
            World world = bot.getWorldServer();
            try {
                Party authoritative = world.getParty(party.getId());
                if (authoritative != null && authoritative.getMemberById(bot.getId()) != null) {
                    PartyCharacter mpc = bot.getMPC();
                    if (mpc != null) {
                        world.updateParty(party.getId(), PartyOperation.LEAVE, mpc);
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Companion world-party leave failed companion={}: {}",
                        bot.getId(), e.getMessage());
                return "world-party detach failed; state held for retry";
            }
            Party authoritative = world.getParty(party.getId());
            if (authoritative != null && authoritative.getMemberById(bot.getId()) != null) {
                return "world-party still contains companion; state held for retry";
            }
            try {
                if (map != null) {
                    map.removePartyMember(bot, party.getId());
                }
            } catch (RuntimeException e) {
                log.warn("Companion map-party leave failed companion={}: {}",
                        bot.getId(), e.getMessage());
                return "map-party detach failed; state held for retry";
            }
            bot.setParty(null);
            bot.setMPC(null);
        }

        if (map != null && isAttached(map, bot)) {
            try {
                map.removePlayer(bot);
            } catch (RuntimeException e) {
                log.warn("Companion map detach failed companion={}: {}",
                        bot.getId(), e.getMessage());
            }
            if (isAttached(map, bot)) {
                return "map detach failed; state held for retry";
            }
        }
        return null;
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
    public Result transferToMap(CompanionSession session, Character bot,
                                MapleMap source, MapleMap destination,
                                Point destinationAnchor) {
        if (session == null || bot == null || source == null
                || destination == null || destinationAnchor == null) {
            return Result.fail("transfer requires session, bot, source, destination and anchor");
        }
        if (source.getId() == destination.getId()) {
            return Result.fail("source and destination are the same map");
        }
        Optional<CompanionMovementService.GroundedPosition> destinationGround =
                movement.resolveGround(destination, destinationAnchor);
        if (destinationGround.isEmpty()) {
            return Result.fail("destination has no valid foothold below the owner");
        }

        Point sourcePosition = bot.getPosition() == null
                ? null : new Point(bot.getPosition());
        Optional<CompanionMovementService.GroundedPosition> sourceGround =
                movement.resolveGround(source, sourcePosition);
        Party party = bot.getParty();
        PartyCharacter mpc = bot.getMPC();
        int previousMpcMapId = mpc == null ? source.getId() : mpc.getMapId();
        boolean sourceDetached = false;
        try {
            source.removePlayer(bot);
            sourceDetached = true;
            bot.setMap(destination);
            bot.setPosition(destinationGround.get().position());
            destination.addPlayer(bot);
            movement.moveAndBroadcast(destination, bot, destinationGround.get());

            if (party != null && mpc != null) {
                mpc.setMapId(destination.getId());
                bot.getWorldServer().updateParty(
                        party.getId(), PartyOperation.SILENT_UPDATE, mpc);
                bot.receivePartyMemberHP();
                bot.updatePartyMemberHP();
            }
        } catch (RuntimeException e) {
            log.error("Companion transfer failed companion={}: {}",
                    bot.getId(), e.getMessage());
            if (!sourceDetached && !isAttached(source, bot)) {
                sourceDetached = true;
            }
            if (sourceDetached) {
                boolean restored = rollbackTransfer(bot, source, destination, sourcePosition,
                        sourceGround, party, mpc, previousMpcMapId);
                if (!restored) {
                    session.compareAndSetState(
                            CompanionSession.State.ACTIVE, CompanionSession.State.SAVE_FAILED);
                    return Result.fail("transfer failed and map cleanup is incomplete: "
                            + e.getMessage());
                }
            }
            return Result.fail("transfer failed: " + e.getMessage());
        }
        return Result.ok(bot);
    }

    private boolean rollbackTransfer(Character bot, MapleMap source, MapleMap destination,
                                     Point sourcePosition,
                                     Optional<CompanionMovementService.GroundedPosition> sourceGround,
                                     Party party, PartyCharacter mpc, int previousMpcMapId) {
        try {
            if (isAttached(destination, bot)) {
                destination.removePlayer(bot);
            }
        } catch (RuntimeException e) {
            log.warn("Companion destination rollback detach failed companion={}: {}",
                    bot.getId(), e.getMessage());
        }
        if (isAttached(destination, bot)) {
            bot.setMap(destination);
            if (mpc != null) {
                mpc.setMapId(destination.getId());
            }
            return false;
        }
        try {
            bot.setMap(source);
            if (sourcePosition != null) {
                bot.setPosition(sourcePosition);
            }
            source.addPlayer(bot);
            sourceGround.ifPresent(ground -> movement.moveAndBroadcast(source, bot, ground));
            if (party != null && mpc != null) {
                mpc.setMapId(previousMpcMapId);
                bot.getWorldServer().updateParty(
                        party.getId(), PartyOperation.SILENT_UPDATE, mpc);
                bot.updatePartyMemberHP();
            }
        } catch (RuntimeException e) {
            log.error("Companion rollback to source map failed companion={}: {}",
                    bot.getId(), e.getMessage());
            return false;
        }
        return isAttached(source, bot);
    }

    private static boolean isAttached(MapleMap map, Character bot) {
        return map.getCharacterById(bot.getId()) == bot
                || map.getMapObject(bot.getObjectId()) == bot;
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
