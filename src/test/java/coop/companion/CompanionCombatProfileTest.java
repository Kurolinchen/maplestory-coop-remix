/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.companion;

import client.Job;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice D regression tests for the companion combat profile and the damage
 * ceiling logic.
 *
 * <p>Job families are audited from OUR {@code client/Job.java}, not from
 * external class lists. The damage clamp must guarantee a companion can never
 * exceed a configured fraction of a monster's max HP nor the absolute cap,
 * which is the core "no hidden advantage" integrity rule.
 */
class CompanionCombatProfileTest {

    @Test
    void explorerFirstJobsAreClassified() {
        assertNotNull(CompanionCombatProfile.forJob(Job.WARRIOR.getId()));
        assertNotNull(CompanionCombatProfile.forJob(Job.MAGICIAN.getId()));
        assertNotNull(CompanionCombatProfile.forJob(Job.BOWMAN.getId()));
        assertNotNull(CompanionCombatProfile.forJob(Job.THIEF.getId()));
        assertNotNull(CompanionCombatProfile.forJob(Job.PIRATE.getId()));
    }

    @Test
    void warriorIsMeleeWithoutAmmo() {
        CompanionCombatProfile.Profile p =
                CompanionCombatProfile.forJob(Job.WARRIOR.getId());
        assertEquals(CompanionCombatProfile.AttackStyle.MELEE, p.style());
        assertEquals(CompanionCombatProfile.AmmoKind.NONE, p.ammo());
        assertFalse(p.requiresAmmo());
    }

    @Test
    void magicianIsMagic() {
        CompanionCombatProfile.Profile p =
                CompanionCombatProfile.forJob(Job.MAGICIAN.getId());
        assertEquals(CompanionCombatProfile.AttackStyle.MAGIC, p.style());
        assertTrue(CompanionCombatProfile.isMagic(p));
        assertFalse(p.requiresAmmo());
    }

    @Test
    void bowmanRequiresArrows() {
        CompanionCombatProfile.Profile p =
                CompanionCombatProfile.forJob(Job.BOWMAN.getId());
        assertEquals(CompanionCombatProfile.AttackStyle.RANGED, p.style());
        assertTrue(p.requiresAmmo());
        assertEquals(CompanionCombatProfile.AmmoKind.ARROW, p.ammo());
    }

    @Test
    void thiefDefaultsToClawAndStars() {
        CompanionCombatProfile.Profile p =
                CompanionCombatProfile.forJob(Job.THIEF.getId());
        assertEquals(CompanionCombatProfile.AttackStyle.CLAW, p.style());
        assertEquals(CompanionCombatProfile.AmmoKind.STAR, p.ammo());
    }

    @Test
    void pirateDefaultsToMeleeWithoutAmmo() {
        // Brawler needs no ammunition; the gun profile is resolved from the
        // equipped weapon at attack time.
        CompanionCombatProfile.Profile p =
                CompanionCombatProfile.forJob(Job.PIRATE.getId());
        assertEquals(CompanionCombatProfile.AttackStyle.MELEE, p.style());
        assertEquals(CompanionCombatProfile.AmmoKind.NONE, p.ammo());
    }

    @Test
    void cygnusFirstJobsAreClassified() {
        assertNotNull(CompanionCombatProfile.forJob(Job.DAWNWARRIOR1.getId()));
        assertNotNull(CompanionCombatProfile.forJob(Job.BLAZEWIZARD1.getId()));
        assertNotNull(CompanionCombatProfile.forJob(Job.WINDARCHER1.getId()));
        assertNotNull(CompanionCombatProfile.forJob(Job.NIGHTWALKER1.getId()));
        assertNotNull(CompanionCombatProfile.forJob(Job.THUNDERBREAKER1.getId()));
    }

    @Test
    void legendFirstJobsAreClassified() {
        assertNotNull(CompanionCombatProfile.forJob(Job.ARAN1.getId()));
        assertNotNull(CompanionCombatProfile.forJob(Job.EVAN1.getId()));
    }

    @Test
    void beginnerHasNoCombatProfile() {
        assertNull(CompanionCombatProfile.forJob(Job.BEGINNER.getId()),
                "a Beginner must not be a combat companion");
    }

    @Test
    void damageIsClampedToHpFraction() {
        CompanionCombatController controller = new CompanionCombatController();
        // A huge raw roll against a low-HP monster must be clamped to the
        // configured fraction of max HP.
        int raw = 100_000;
        int clamped = controller.clampDamage(raw, fakeMonster(100));
        assertTrue(clamped <= 100, "damage must not exceed the HP-fraction ceiling, got " + clamped);
        assertTrue(clamped >= 1, "damage must always be at least 1");
    }

    @Test
    void damageNeverFallsBelowOne() {
        CompanionCombatController controller = new CompanionCombatController();
        int clamped = controller.clampDamage(1, fakeMonster(10_000));
        assertTrue(clamped >= 1);
    }

    @Test
    void zeroOrNegativeRawDamageYieldsZero() {
        CompanionCombatController controller = new CompanionCombatController();
        assertEquals(0, controller.clampDamage(0, fakeMonster(100)));
        assertEquals(0, controller.clampDamage(-5, fakeMonster(100)));
    }

    @Test
    void absoluteCapIsRespected() {
        CompanionCombatController controller = new CompanionCombatController();
        // Against a very high-HP target the absolute cap binds instead.
        int clamped = controller.clampDamage(50_000_000, fakeMonster(10_000_000));
        assertTrue(clamped <= coop.config.CoopDefaults.companionOutgoingDamageAbsoluteCap(),
                "absolute damage cap must bind");
    }

    @Test
    void consumableNeedIsNoneWhenHealthy() {
        CompanionConsumableService service = new CompanionConsumableService();
        assertEquals(CompanionConsumableService.Need.NONE, service.evaluate(null));
    }

    @Test
    void ammoKindResolutionRequiresAProfile() {
        // A melee profile never needs ammunition regardless of equipment.
        CompanionCombatProfile.Profile melee =
                CompanionCombatProfile.forJob(Job.WARRIOR.getId());
        assertEquals(CompanionCombatProfile.AmmoKind.NONE,
                CompanionCombatProfile.resolveAmmo(null, melee));
        assertEquals(CompanionCombatProfile.AmmoKind.NONE,
                CompanionCombatProfile.resolveAmmo(null, null));
    }

    /** Minimal Monster stub; only the fields the clamp reads are meaningful. */
    private static server.life.Monster fakeMonster(int maxHp) {
        server.life.Monster monster = org.mockito.Mockito.mock(server.life.Monster.class);
        org.mockito.Mockito.when(monster.getMaxHp()).thenReturn(maxHp);
        return monster;
    }
}
