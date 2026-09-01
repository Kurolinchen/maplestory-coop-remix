/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.companion;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import coop.config.CoopDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.life.Monster;
import server.maps.MapleMap;

import java.util.List;

/**
 * Slice D (Companion combat): one bounded attack per tick.
 *
 * <p>Integrity rules enforced here, all of them non-negotiable:
 * <ul>
 *   <li>damage is derived from the companion's REAL stats and equipment via the
 *       upstream {@code calculateMaxBaseDamage} / {@code calculateMaxBaseMagicDamage}
 *       helpers — there is no bot-specific damage table;</li>
 *   <li>the result is clamped to a configurable fraction of the monster's max HP
 *       plus an absolute ceiling, so a bot can never one-shot content it should
 *       not;</li>
 *   <li>bosses are excluded unless explicitly allowed;</li>
 *   <li>ammunition and MP are consumed through the normal inventory/manipulator
 *       paths — no free attacks, no duplicated items;</li>
 *   <li>all damage is applied through {@code MapleMap.damageMonster}, the same
 *       authoritative path a real player's attack uses, so kill/EXP/drop
 *       accounting cannot be bypassed.</li>
 * </ul>
 */
public final class CompanionCombatController {
    private static final Logger log = LoggerFactory.getLogger(CompanionCombatController.class);

    private long lastAttackAt = 0L;
    private long lastIncomingAt = 0L;

    public record AttackResult(boolean attacked, String reason, int damage, Monster target) {
        public static AttackResult skipped(String reason) {
            return new AttackResult(false, reason, 0, null);
        }
        public static AttackResult hit(Monster target, int damage) {
            return new AttackResult(true, null, damage, target);
        }
    }

    /** Executes at most one attack this tick. */
    public AttackResult tick(CompanionSession session, Character bot) {
        if (session == null || bot == null) {
            return AttackResult.skipped("missing session/bot");
        }
        if (session.mode() != CompanionSession.Mode.GRIND) {
            return AttackResult.skipped("mode is " + session.mode() + ", not GRIND");
        }
        if (!bot.isAlive()) {
            return AttackResult.skipped("companion is dead");
        }
        long now = System.currentTimeMillis();
        if (now - lastAttackAt < CoopDefaults.companionAttackIntervalMs()) {
            return AttackResult.skipped("attack cooldown");
        }

        CompanionCombatProfile.Profile profile =
                CompanionCombatProfile.forJob(bot.getJob().getId());
        if (profile == null) {
            CompanionCombatProfile.logUnknownJob(bot.getJob().getId());
            return AttackResult.skipped("job has no supported combat profile");
        }

        MapleMap map = bot.getMap();
        if (map == null) {
            return AttackResult.skipped("companion has no map");
        }
        Monster target = selectTarget(bot, map, profile);
        if (target == null) {
            return AttackResult.skipped("no valid target");
        }
        if (!hasRequiredResources(bot, profile)) {
            return AttackResult.skipped("missing ammunition or MP");
        }

        int damage = computeDamage(bot, target, profile);
        if (damage <= 0) {
            return AttackResult.skipped("computed damage is zero");
        }
        consumeResources(bot, profile);

        lastAttackAt = now;
        map.damageMonster(bot, target, damage);
        return AttackResult.hit(target, damage);
    }

    /** Nearest alive, in-range, non-boss monster within the configured level band. */
    public Monster selectTarget(Character bot, MapleMap map, CompanionCombatProfile.Profile profile) {
        int maxRangeSq = CoopDefaults.companionTargetRange() * CoopDefaults.companionTargetRange();
        int levelDelta = CoopDefaults.companionTargetLevelDelta();
        int botLevel = bot.getLevel();

        Monster best = null;
        double bestDistanceSq = Double.POSITIVE_INFINITY;
        for (server.maps.MapObject obj : map.getMapObjects()) {
            if (!(obj instanceof Monster monster)) {
                continue;
            }
            if (!monster.isAlive()) {
                continue;
            }
            if (monster.isBoss() && !CoopDefaults.companionAllowBosses()) {
                continue;
            }
            if (Math.abs(monster.getLevel() - botLevel) > levelDelta) {
                continue;
            }
            double distanceSq = monster.getPosition().distanceSq(bot.getPosition());
            if (distanceSq > maxRangeSq || distanceSq >= bestDistanceSq) {
                continue;
            }
            bestDistanceSq = distanceSq;
            best = monster;
        }
        return best;
    }

    /**
     * Damage from the companion's real stats, then clamped.
     * Visible for testing via {@link #clampDamage}.
     */
    public int computeDamage(Character bot, Monster target,
                             CompanionCombatProfile.Profile profile) {
        int raw;
        if (CompanionCombatProfile.isMagic(profile)) {
            raw = bot.calculateMaxBaseMagicDamage(bot.getTotalMagic());
        } else {
            raw = bot.calculateMaxBaseDamage(bot.getTotalWatk());
        }
        return clampDamage(raw, target);
    }

    /** Applies the configured min/max ratio and both damage ceilings. */
    public int clampDamage(int raw, Monster target) {
        if (raw <= 0) {
            return 0;
        }
        double minRatio = CoopDefaults.companionOutgoingDamageMinRatio();
        double maxRatio = CoopDefaults.companionOutgoingDamageMaxRatio();
        int min = (int) Math.floor(raw * minRatio);
        int max = (int) Math.ceil(raw * maxRatio);
        int roll = min + (int) Math.floor(Math.random() * Math.max(1, max - min + 1));

        int hpCap = target != null
                ? (int) Math.floor(target.getMaxHp() * CoopDefaults.companionOutgoingDamageHpCapRatio())
                : Integer.MAX_VALUE;
        int absoluteCap = CoopDefaults.companionOutgoingDamageAbsoluteCap();
        return Math.max(1, Math.min(roll, Math.min(hpCap, absoluteCap)));
    }

    /**
     * Applies bounded incoming contact damage so a companion is not immortal.
     * Only the nearest monster's contact damage is modelled; mob skills,
     * diseases and reflect are out of scope for the MVP.
     */
    public void applyIncomingDamage(CompanionSession session, Character bot) {
        if (!CoopDefaults.companionIncomingDamageEnabled() || bot == null || !bot.isAlive()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastIncomingAt < CoopDefaults.companionIncomingDamageIntervalMs()) {
            return;
        }
        MapleMap map = bot.getMap();
        if (map == null) {
            return;
        }
        int contactRangeSq = CoopDefaults.companionIncomingContactRange()
                * CoopDefaults.companionIncomingContactRange();
        Monster nearest = null;
        double nearestSq = Double.POSITIVE_INFINITY;
        for (server.maps.MapObject obj : map.getMapObjects()) {
            if (!(obj instanceof Monster monster) || !monster.isAlive()) {
                continue;
            }
            double distanceSq = monster.getPosition().distanceSq(bot.getPosition());
            if (distanceSq <= contactRangeSq && distanceSq < nearestSq) {
                nearestSq = distanceSq;
                nearest = monster;
            }
        }
        if (nearest == null) {
            return;
        }
        int attack = Math.max(1, nearest.getStats().getPADamage());
        int damage = (int) Math.ceil(attack * CoopDefaults.companionIncomingDamageMaxRatio());
        damage = Math.max(CoopDefaults.companionIncomingDamageMin(), damage);
        lastIncomingAt = now;
        bot.addHP(-damage);
    }

    private boolean hasRequiredResources(Character bot, CompanionCombatProfile.Profile profile) {
        CompanionCombatProfile.AmmoKind ammo =
                CompanionCombatProfile.resolveAmmo(bot, profile);
        if (ammo != CompanionCombatProfile.AmmoKind.NONE && !hasAmmo(bot, ammo)) {
            return false;
        }
        if (CompanionCombatProfile.isMagic(profile) && bot.getMp() <= 0) {
            return false;
        }
        return true;
    }

    private void consumeResources(Character bot, CompanionCombatProfile.Profile profile) {
        CompanionCombatProfile.AmmoKind ammo =
                CompanionCombatProfile.resolveAmmo(bot, profile);
        if (ammo != CompanionCombatProfile.AmmoKind.NONE) {
            consumeAmmo(bot, ammo);
        }
    }

    /** Consumes exactly one unit of the correct ammunition type. */
    public boolean consumeAmmo(Character bot, CompanionCombatProfile.AmmoKind kind) {
        int slot = findAmmoSlot(bot, kind);
        if (slot < 0) {
            return false;
        }
        Item item = bot.getInventory(InventoryType.USE).getItem((byte) slot);
        if (item == null) {
            return false;
        }
        InventoryManipulator.removeFromSlot(bot.getClient(), InventoryType.USE,
                (byte) slot, item.getQuantity() > 1 ? (short) 1 : item.getQuantity(), false);
        return true;
    }

    public boolean hasAmmo(Character bot, CompanionCombatProfile.AmmoKind kind) {
        return findAmmoSlot(bot, kind) >= 0;
    }

    private int findAmmoSlot(Character bot, CompanionCombatProfile.AmmoKind kind) {
        List<Integer> ids = switch (kind) {
            case ARROW -> List.of(2060000, 2061000);
            case BOLT -> List.of(2061000);
            case BULLET -> List.of(2330000, 2330001, 2330002, 2330003, 2330004, 2330005, 2330006);
            case STAR -> List.of(2070000, 2070001, 2070002, 2070003, 2070004,
                    2070005, 2070006, 2070007, 2070008, 2070009,
                    2070010, 2070011, 2070012, 2070013);
            case NONE -> List.of();
        };
        if (ids.isEmpty()) {
            return -1;
        }
        for (Item item : bot.getInventory(InventoryType.USE).list()) {
            if (item != null && item.getQuantity() > 0 && ids.contains(item.getItemId())) {
                return item.getPosition();
            }
        }
        return -1;
    }
}
