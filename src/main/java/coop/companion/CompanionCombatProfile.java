/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.companion;

import client.Character;
import client.Job;
import client.inventory.WeaponType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.life.Monster;

/**
 * Slice D (Companion combat): pure classification of a companion's combat
 * profile from its job, so the rest of the combat code can stay data-driven.
 *
 * <p>Job families audited from OUR tree ({@code client/Job.java}), not from
 * external guides:
 * <ul>
 *   <li><b>Explorer</b> first jobs: WARRIOR(100) melee, MAGICIAN(200) magic,
 *       BOWMAN(300) ranged, THIEF(400) ranged-claw or melee-dagger, PIRATE(500)
 *       melee-knuckle or ranged-gun.</li>
 *   <li><b>Cygnus</b> first jobs: DAWNWARRIOR1(1100) melee, BLAZEWIZARD1(1200)
 *       magic, WINDARCHER1(1300) ranged, NIGHTWALKER1(1400) ranged-claw,
 *       THUNDERBREAKER1(1500) melee.</li>
 *   <li><b>Legend</b>: ARAN1(2100) melee, EVAN1(2200) magic.</li>
 * </ul>
 *
 * <p>Beginner (job 0) and NOBLESSE (1000) are intentionally NOT combat
 * profiles — a companion must have made its first job advancement.
 */
public final class CompanionCombatProfile {
    private static final Logger log = LoggerFactory.getLogger(CompanionCombatProfile.class);

    private CompanionCombatProfile() {
    }

    public enum AttackStyle {
        MELEE,      // close-range physical hit
        RANGED,     // requires ammunition (arrows / bolts / bullets)
        CLAW,       // requires throwing stars
        MAGIC       // magic attack, consumes MP
    }

    public enum AmmoKind {
        NONE, ARROW, BOLT, BULLET, STAR
    }

    public record Profile(Job family, AttackStyle style, AmmoKind ammo,
                          boolean usesMpForBasicAttack) {
        public boolean requiresAmmo() {
            return ammo != AmmoKind.NONE;
        }
    }

    /**
     * Classifies a job id into a combat profile, or returns null when the job
     * has no supported first-job profile.
     */
    public static Profile forJob(int jobId) {
        Job job = Job.getById(jobId);
        if (job == null) {
            return null;
        }
        if (job.isA(Job.WARRIOR)) {
            return new Profile(Job.WARRIOR, AttackStyle.MELEE, AmmoKind.NONE, false);
        }
        if (job.isA(Job.MAGICIAN)) {
            return new Profile(Job.MAGICIAN, AttackStyle.MAGIC, AmmoKind.NONE, false);
        }
        if (job.isA(Job.BOWMAN)) {
            // Bow vs crossbow is decided at attack time from the equipped weapon.
            return new Profile(Job.BOWMAN, AttackStyle.RANGED, AmmoKind.ARROW, false);
        }
        if (job.isA(Job.THIEF)) {
            // Assassin uses claws+stars, Bandit uses a dagger. Decided at attack
            // time from the equipped weapon; default to the claw/star profile
            // because that is the common first-job Assassin choice.
            return new Profile(Job.THIEF, AttackStyle.CLAW, AmmoKind.STAR, false);
        }
        if (job.isA(Job.PIRATE)) {
            // Gunslinger uses bullets, Brawler uses knuckles. Decided at attack
            // time; default to melee because Brawler needs no ammunition.
            return new Profile(Job.PIRATE, AttackStyle.MELEE, AmmoKind.NONE, false);
        }
        if (job.isA(Job.DAWNWARRIOR1)) {
            return new Profile(Job.DAWNWARRIOR1, AttackStyle.MELEE, AmmoKind.NONE, false);
        }
        if (job.isA(Job.BLAZEWIZARD1)) {
            return new Profile(Job.BLAZEWIZARD1, AttackStyle.MAGIC, AmmoKind.NONE, false);
        }
        if (job.isA(Job.WINDARCHER1)) {
            return new Profile(Job.WINDARCHER1, AttackStyle.RANGED, AmmoKind.ARROW, false);
        }
        if (job.isA(Job.NIGHTWALKER1)) {
            return new Profile(Job.NIGHTWALKER1, AttackStyle.CLAW, AmmoKind.STAR, false);
        }
        if (job.isA(Job.THUNDERBREAKER1)) {
            return new Profile(Job.THUNDERBREAKER1, AttackStyle.MELEE, AmmoKind.NONE, false);
        }
        if (job.isA(Job.ARAN1)) {
            return new Profile(Job.ARAN1, AttackStyle.MELEE, AmmoKind.NONE, false);
        }
        if (job.isA(Job.EVAN1)) {
            return new Profile(Job.EVAN1, AttackStyle.MAGIC, AmmoKind.NONE, false);
        }
        // Beginner / Noblesse / unmapped: no combat profile.
        return null;
    }

    /**
     * Refines the ammo kind from the actually equipped weapon, because Thief and
     * Pirate first jobs branch on weapon choice rather than on job id.
     *
     * <p>Mirrors the upstream resolution path ({@code Character.calculateMaxBaseDamage}):
     * the weapon item id is mapped through {@code ItemInformationProvider.getWeaponType}.
     */
    public static AmmoKind resolveAmmo(Character chr, Profile base) {
        if (chr == null || base == null || !base.requiresAmmo()) {
            return AmmoKind.NONE;
        }
        WeaponType weapon = equippedWeaponType(chr);
        if (weapon == null) {
            return base.ammo();
        }
        return switch (weapon) {
            case BOW -> AmmoKind.ARROW;
            case CROSSBOW -> AmmoKind.BOLT;
            case GUN -> AmmoKind.BULLET;
            case CLAW -> AmmoKind.STAR;
            default -> base.ammo();
        };
    }

    /** Returns the equipped weapon's {@link WeaponType}, or null when unarmed. */
    public static WeaponType equippedWeaponType(Character chr) {
        if (chr == null) {
            return null;
        }
        client.inventory.Item weapon = chr.getInventory(client.inventory.InventoryType.EQUIPPED)
                .getItem((byte) -11);
        if (weapon == null) {
            return null;
        }
        try {
            return server.ItemInformationProvider.getInstance().getWeaponType(weapon.getItemId());
        } catch (RuntimeException e) {
            log.warn("Could not resolve weapon type for item {}: {}", weapon.getItemId(), e.getMessage());
            return null;
        }
    }

    /** Whether this profile's basic attack is a magic (MP-consuming) attack. */
    public static boolean isMagic(Profile profile) {
        return profile != null && profile.style() == AttackStyle.MAGIC;
    }

    public static void logUnknownJob(int jobId) {
        log.debug("Companion job {} has no supported combat profile", jobId);
    }
}
