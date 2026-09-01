/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.companion;

import client.Character;
import coop.config.CoopConfig;
import coop.config.CoopDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;

import java.util.Optional;

/**
 * Slice B (Companion Bot MVP) controller owned by {@code @companion} commands.
 *
 * <p>Holds every policy decision that must be identical whether the caller is a
 * player command, the owner-disconnect hook or the server-shutdown hook:
 * <ul>
 *   <li>feature-enablement gate</li>
 *   <li>ownership verification (same account, same world, distinct ids)</li>
 *   <li>active-slot limits per owner / per account</li>
 *   <li>explicit re-verification at spawn time (never trust cached bindings)</li>
 * </ul>
 */
public final class CompanionController {
    private static final Logger log = LoggerFactory.getLogger(CompanionController.class);

    private static final CompanionController INSTANCE = new CompanionController();

    public static CompanionController getInstance() {
        return INSTANCE;
    }

    private final CompanionManager manager = CompanionManager.getInstance();
    private final CompanionLifecycleService lifecycle = CompanionLifecycleService.getInstance();
    private final CompanionEquipmentService equipment = new CompanionEquipmentService();

    private CompanionController() {
    }

    public record Outcome(boolean success, String message) {
        public static Outcome ok(String message) {
            return new Outcome(true, message);
        }
        public static Outcome fail(String message) {
            return new Outcome(false, message);
        }
    }

    public boolean isEnabled() {
        return CoopDefaults.companionEnabled();
    }

    /** Verifies the feature is enabled and the character is not already an owner or companion. */
    public Outcome bind(Character owner, int companionCharacterId) {
        if (!isEnabled()) {
            return Outcome.fail("Companions are disabled on this server.");
        }
        int ownerId = owner.getId();
        if (ownerId == companionCharacterId) {
            return Outcome.fail("You cannot bind yourself as your own companion.");
        }
        if (manager.isCompanionActive(ownerId)) {
            return Outcome.fail("This character is already active as a companion.");
        }
        CompanionBindingRepository.OwnershipCheckResult check =
                CompanionBindingRepository.verifyOwnership(
                        owner.getAccountID(), ownerId, companionCharacterId);
        if (!check.allowed()) {
            return Outcome.fail("Cannot bind: " + check.reason());
        }
        if (!isJobTierAllowed(check.companionJob())) {
            return Outcome.fail("Companion job is not supported yet (only first-job characters).");
        }
        if (manager.isCompanionActive(companionCharacterId)
                || isOnlineInWorld(check.world(), companionCharacterId)) {
            return Outcome.fail("That character is currently in use. Log it out first.");
        }

        // Single upsert instead of delete-then-insert: the earlier revision
        // deleted the owner's existing binding first, so a UNIQUE-constraint
        // failure on the insert silently destroyed the previous binding.
        boolean ok = CompanionBindingRepository.upsert(ownerId, companionCharacterId,
                owner.getAccountID(), check.world(), CompanionSession.Mode.PASSIVE.name(),
                CoopDefaults.companionLootEnabledDefault());
        if (!ok) {
            return Outcome.fail("Binding failed; the character may already be bound to another owner.");
        }
        log.info("Companion bound: owner={} companion={} account={}",
                ownerId, companionCharacterId, owner.getAccountID());
        return Outcome.ok("Companion bound: " + check.companionName() + ".");
    }

    public Outcome unbind(Character owner) {
        int ownerId = owner.getId();
        Optional<CompanionSession> live = manager.findByOwner(ownerId);
        if (live.isPresent()) {
            CompanionSession session = live.get();
            Character bot = findCompanionCharacter(session);
            CompanionLifecycleService.Result result = lifecycle.dismiss(session, bot);
            if (result.success()) {
                manager.release(session);
                CompanionTickScheduler.getInstance().forgetTracker(ownerId);
            } else {
                return Outcome.fail("Could not dismiss the active companion: " + result.reason());
            }
        }
        boolean deleted = CompanionBindingRepository.delete(ownerId);
        if (!deleted) {
            return Outcome.fail("No companion binding to clear.");
        }
        return Outcome.ok("Companion binding cleared.");
    }

    public Outcome spawn(Character owner) {
        if (!isEnabled()) {
            return Outcome.fail("Companions are disabled on this server.");
        }
        if (manager.isShuttingDown()) {
            return Outcome.fail("Server is shutting down; companions cannot be spawned.");
        }
        int ownerId = owner.getId();
        if (manager.findByOwner(ownerId).isPresent()) {
            return Outcome.fail("You already have an active companion.");
        }
        if (manager.isCompanionActive(ownerId)) {
            return Outcome.fail("This character is active as a companion elsewhere.");
        }
        int accountActive = countActiveForAccount(owner.getAccountID());
        if (accountActive >= CoopDefaults.companionMaxActivePerAccount()) {
            return Outcome.fail("Account companion limit reached.");
        }

        Optional<CompanionBindingRepository.Binding> binding =
                CompanionBindingRepository.findByOwner(ownerId);
        if (binding.isEmpty()) {
            return Outcome.fail("No companion bound. Use @companion bind <character-name> first.");
        }
        CompanionBindingRepository.Binding b = binding.get();

        // Re-verify ownership at spawn time: the binding table could be stale.
        CompanionBindingRepository.OwnershipCheckResult check =
                CompanionBindingRepository.verifyOwnership(
                        owner.getAccountID(), ownerId, b.companionCharacterId());
        if (!check.allowed()) {
            return Outcome.fail("Companion no longer valid: " + check.reason());
        }
        if (manager.isCompanionActive(b.companionCharacterId())) {
            return Outcome.fail("That companion is already active.");
        }
        // A companion is loaded straight through Character.loadCharFromDB, so if
        // the same character is ALSO logged in normally we would end up with two
        // live Character objects for one DB row - last write wins, which loses
        // items/meso/EXP or duplicates them on interleaved saves.
        if (isOnlineInWorld(check.world(), b.companionCharacterId())) {
            return Outcome.fail("That character is currently logged in. Log it out first.");
        }
        if (!isJobTierAllowed(check.companionJob())) {
            return Outcome.fail("Companion job is not supported yet (only first-job characters).");
        }
        MapleMap map = owner.getMap();
        if (map == null || !isMapAllowed(map.getId())) {
            return Outcome.fail("Companions cannot be spawned on this map.");
        }
        // Entry scripts re-run for the bot on every spawn, which would turn
        // spawn/dismiss cycles into a reward farm on scripted maps.
        if (CompanionMapPolicy.hasEntryScript(map)) {
            return Outcome.fail("This map runs an entry script; companions are not allowed here.");
        }

        CompanionSession session = new CompanionSession(ownerId, b.companionCharacterId(),
                owner.getAccountID(), check.world(), owner.getClient().getChannel(),
                CompanionSession.Mode.parse(b.mode(), CompanionSession.Mode.PASSIVE),
                b.lootEnabled());
        if (!manager.register(session)) {
            return Outcome.fail("Could not reserve a companion slot.");
        }

        CompanionLifecycleService.Result result = lifecycle.spawn(owner, session);
        if (!result.success()) {
            manager.release(session);
            return Outcome.fail("Spawn failed: " + result.reason());
        }
        // Slice C: start the shared follow/navigation tick loop (idempotent).
        CompanionTickScheduler.getInstance().start();
        return Outcome.ok("Companion " + check.companionName() + " spawned.");
    }

    public Outcome dismiss(Character owner) {
        Optional<CompanionSession> live = manager.findByOwner(owner.getId());
        if (live.isEmpty()) {
            return Outcome.fail("You have no active companion.");
        }
        CompanionSession session = live.get();
        Character bot = findCompanionCharacter(session);
        if (bot == null) {
            return Outcome.fail("Companion is not reachable on this map; it could not be saved. "
                    + "Move back to its map or let the server shut down cleanly.");
        }
        CompanionLifecycleService.Result result = lifecycle.dismiss(session, bot);
        if (!result.success()) {
            // SAVE_FAILED: keep the session registered so state is retried.
            return Outcome.fail("Dismiss failed: " + result.reason());
        }
        manager.release(session);
        CompanionTickScheduler.getInstance().forgetTracker(owner.getId());
        return Outcome.ok("Companion dismissed.");
    }

    /**
     * Changes the companion's behaviour mode. GRIND is the only mode that
     * attacks; PASSIVE/FOLLOW never initiate combat. The mode is persisted so a
     * later spawn resumes the same behaviour.
     */
    public Outcome setMode(Character owner, String rawMode) {
        CompanionSession.Mode mode =
                CompanionSession.Mode.parse(rawMode, null);
        if (mode == null) {
            return Outcome.fail("Unknown mode '" + rawMode
                    + "'. Use passive|follow|grind|support|stay.");
        }
        if (mode == CompanionSession.Mode.SUPPORT) {
            return Outcome.fail("The support mode is not implemented yet (Slice D follow-up).");
        }
        Optional<CompanionSession> live = manager.findByOwner(owner.getId());
        if (live.isPresent()) {
            // Live session: the in-memory snapshot drives the tick loop, so we
            // cannot mutate its final mode field. Report how to apply it.
            return Outcome.fail("Companion is active. Use @companion dismiss, then set the mode, then spawn again.");
        }
        if (CompanionBindingRepository.findByOwner(owner.getId()).isEmpty()) {
            return Outcome.fail("No companion bound. Use @companion bind <character-name> first.");
        }
        boolean ok = lifecycle.persistMode(owner.getId(), mode,
                CoopDefaults.companionLootEnabledDefault());
        if (!ok) {
            return Outcome.fail("Could not persist mode; check server logs.");
        }
        return Outcome.ok("Companion mode set to " + mode.name()
                + ". It will apply the next time you spawn the companion.");
    }

    /**
     * Toggles companion looting for this binding. Looting stays opt-in because a
     * bot vacuuming party loot makes group play worse; it is persisted so the
     * choice survives a dismiss/spawn cycle.
     */
    public Outcome setLoot(Character owner, boolean enabled) {
        if (CompanionBindingRepository.findByOwner(owner.getId()).isEmpty()) {
            return Outcome.fail("No companion bound. Use @companion bind <character-name> first.");
        }
        Optional<CompanionSession> live = manager.findByOwner(owner.getId());
        if (live.isPresent()) {
            return Outcome.fail("Companion is active. Dismiss it before changing loot settings.");
        }
        CompanionBindingRepository.Binding current =
                CompanionBindingRepository.findByOwner(owner.getId()).get();
        boolean ok = lifecycle.persistMode(owner.getId(),
                CompanionSession.Mode.parse(current.mode(), CompanionSession.Mode.PASSIVE),
                enabled);
        if (!ok) {
            return Outcome.fail("Could not persist loot setting; check server logs.");
        }
        return Outcome.ok("Companion looting " + (enabled ? "enabled" : "disabled") + ".");
    }

    /**
     * Owner-directed equip. Slice E deliberately has no automatic upgrade
     * optimiser: every change goes through the normal wear validation path.
     */
    public Outcome equip(Character owner, int sourceSlot, short targetSlot) {
        Optional<CompanionSession> live = manager.findByOwner(owner.getId());
        if (live.isEmpty()) {
            return Outcome.fail("You have no active companion.");
        }
        CompanionSession session = live.get();
        Character bot = findCompanionCharacter(session);
        if (bot == null) {
            return Outcome.fail("Companion is not reachable right now.");
        }
        CompanionEquipmentService.EquipResult result =
                equipment.equip(bot, sourceSlot, targetSlot);
        if (!result.success()) {
            return Outcome.fail(result.reason());
        }
        return Outcome.ok("Companion equipped slot " + sourceSlot + ".");
    }

    public String status(Character owner) {
        Optional<CompanionBindingRepository.Binding> binding =
                CompanionBindingRepository.findByOwner(owner.getId());
        StringBuilder sb = new StringBuilder();
        sb.append("Companions: ").append(isEnabled() ? "enabled" : "disabled");
        if (binding.isEmpty()) {
            sb.append(" | no companion bound (use @companion bind <name>)");
            return sb.toString();
        }
        CompanionBindingRepository.Binding b = binding.get();
        sb.append(" | bound companion id=").append(b.companionCharacterId())
                .append(" mode=").append(b.mode())
                .append(" loot=").append(b.lootEnabled());
        Optional<CompanionSession> live = manager.findByOwner(owner.getId());
        if (live.isEmpty()) {
            sb.append(" | inactive (use @companion spawn)");
        } else {
            sb.append(" | ").append(live.get().snapshot());
        }
        return sb.toString();
    }

    /** Called from the owner-disconnect hook: dismiss and release synchronously. */
    public void onOwnerDisconnect(Character owner) {
        Optional<CompanionSession> live = manager.findByOwner(owner.getId());
        if (live.isEmpty()) {
            return;
        }
        CompanionSession session = live.get();
        Character bot = findCompanionCharacter(session);
        CompanionLifecycleService.Result result = lifecycle.dismiss(session, bot);
        if (!result.success()) {
            // Hold the session in SAVE_FAILED rather than dropping the character.
            log.error("Companion auto-dismiss failed owner={} companion={}: {}",
                    owner.getId(), session.companionCharacterId(), result.reason());
            return;
        }
        manager.release(session);
        CompanionTickScheduler.getInstance().forgetTracker(owner.getId());
        log.info("Companion auto-dismissed on owner disconnect: owner={}", owner.getId());
    }

    /** Called from the server-shutdown hook before worlds/channels are disposed. */
    public void shutdownAll() {
        manager.beginShutdown();
        for (CompanionSession session : manager.activeSessions()) {
            Character bot = findCompanionCharacter(session);
            CompanionLifecycleService.Result result = lifecycle.dismiss(session, bot);
            if (!result.success()) {
                log.error("Companion shutdown save FAILED owner={} companion={}: {}",
                        session.ownerCharacterId(), session.companionCharacterId(), result.reason());
                continue;
            }
            manager.release(session);
            CompanionTickScheduler.getInstance().forgetTracker(session.ownerCharacterId());
        }
        log.info("Companion shutdown complete ({} remaining)", manager.activeOwnerCount());
    }

    /**
     * Whether the character is currently logged in as a normal player in the
     * given world. Used to refuse binding/spawning a character that already has
     * a live session, which would otherwise create two savers for one row.
     */
    private boolean isOnlineInWorld(int world, int characterId) {
        try {
            net.server.world.World w = net.server.Server.getInstance().getWorld(world);
            return w != null && w.getPlayerStorage().getCharacterById(characterId) != null;
        } catch (RuntimeException e) {
            // Conservative: if we cannot prove it is offline, refuse.
            log.warn("Could not determine online state for character {}: {}",
                    characterId, e.getMessage());
            return true;
        }
    }

    private int countActiveForAccount(int accountId) {
        int count = 0;
        for (CompanionSession s : manager.activeSessions()) {
            if (s.accountId() == accountId) {
                count++;
            }
        }
        return count;
    }

    /**
     * A map is eligible only when it is BOTH explicitly allowlisted AND not on
     * the hard blocklist. The blocklist check is not redundant with the
     * allowlist: operators extend `allowed_map_ids` by hand, and adding a PQ
     * lobby, dojo or event field id would otherwise let a companion spawn into
     * instanced content and satisfy a party-size check.
     */
    private boolean isMapAllowed(int mapId) {
        java.util.List<Integer> allowed = CoopDefaults.companionAllowedMapIds();
        if (allowed.isEmpty() || !allowed.contains(mapId)) {
            return false;
        }
        return !CompanionMapPolicy.isBlocked(mapId);
    }

    /**
     * Whether the job's ADVANCEMENT TIER is within the configured limit.
     *
     * <p>MapleStory job ids encode both the class family and the advancement
     * tier: the last two digits are the branch/tier code.
     * <pre>
     *   100  -> 0   -> tier 1 (Warrior, Magician, Bowman, Thief, Pirate,
     *                          Noblesse, DawnWarrior1, ..., Evan1)
     *   110/120/130 -> 10/20/30 -> tier 2 (Fighter/Page/Spearman, ...)
     *   111/121/131 -> tier 3,  112/122/132 -> tier 4
     * </pre>
     * The earlier implementation used {@code (jobId % 1000) / 100}, which is the
     * FAMILY digit, not the tier: with a limit of 1 it admitted only Warrior
     * (100) and Dawn Warrior (1100) and silently rejected Magician, Bowman,
     * Thief, Pirate and every other Cygnus/Legend first job.
     */
    static boolean isJobTierWithinLimit(int jobId, int tierLimit) {
        if (jobId <= 0 || tierLimit < 1) {
            return false;
        }
        int branch = jobId % 100;
        int tier = (branch == 0) ? 1 : (branch % 10) + 2;
        return tier <= tierLimit;
    }

    private boolean isJobTierAllowed(int jobId) {
        return isJobTierWithinLimit(jobId, CoopDefaults.companionAllowedJobTier());
    }

    /**
     * Locates the live companion character object. The companion is not in
     * PlayerStorage (by design), so we look it up through the owner's current
     * map, which is the only place the bot is attached.
     */
    private Character findCompanionCharacter(CompanionSession session) {
        Character owner = findOnlineOwner(session);
        if (owner == null) {
            return null;
        }
        MapleMap map = owner.getMap();
        if (map == null) {
            return null;
        }
        return map.getCharacterById(session.companionCharacterId());
    }

    private Character findOnlineOwner(CompanionSession session) {
        try {
            net.server.world.World world = net.server.Server.getInstance()
                    .getWorld(session.world());
            if (world == null) {
                return null;
            }
            return world.getPlayerStorage().getCharacterById(session.ownerCharacterId());
        } catch (RuntimeException e) {
            log.warn("Could not resolve owner {} for companion lookup: {}",
                    session.ownerCharacterId(), e.getMessage());
            return null;
        }
    }
}
