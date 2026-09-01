/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.companion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the defects found in the companion code review.
 *
 * <p>Each test corresponds to a review finding that would have broken the first
 * gameplay test outright, so they are pinned here rather than only in the manual
 * checklist.
 */
class CompanionDefectRegressionTest {

    // ------------------------------------------------------------------
    // P0-1: job TIER, not job FAMILY
    // ------------------------------------------------------------------

    @Test
    void firstJobOfEveryExplorerFamilyPassesTierOne() {
        // The original formula (jobId % 1000) / 100 is the FAMILY digit: with a
        // limit of 1 it admitted only Warrior (100) and rejected every other
        // first job. All five explorers must pass.
        assertTrue(CompanionController.isJobTierWithinLimit(100, 1), "Warrior");
        assertTrue(CompanionController.isJobTierWithinLimit(200, 1), "Magician");
        assertTrue(CompanionController.isJobTierWithinLimit(300, 1), "Bowman");
        assertTrue(CompanionController.isJobTierWithinLimit(400, 1), "Thief");
        assertTrue(CompanionController.isJobTierWithinLimit(500, 1), "Pirate");
    }

    @Test
    void firstJobOfEveryCygnusAndLegendFamilyPassesTierOne() {
        assertTrue(CompanionController.isJobTierWithinLimit(1100, 1), "Dawn Warrior");
        assertTrue(CompanionController.isJobTierWithinLimit(1200, 1), "Blaze Wizard");
        assertTrue(CompanionController.isJobTierWithinLimit(1300, 1), "Wind Archer");
        assertTrue(CompanionController.isJobTierWithinLimit(1400, 1), "Night Walker");
        assertTrue(CompanionController.isJobTierWithinLimit(1500, 1), "Thunder Breaker");
        assertTrue(CompanionController.isJobTierWithinLimit(2100, 1), "Aran");
        assertTrue(CompanionController.isJobTierWithinLimit(2200, 1), "Evan");
    }

    @Test
    void secondJobAndBeyondAreRejectedAtTierOne() {
        assertFalse(CompanionController.isJobTierWithinLimit(110, 1), "Fighter is tier 2");
        assertFalse(CompanionController.isJobTierWithinLimit(120, 1), "Page is tier 2");
        assertFalse(CompanionController.isJobTierWithinLimit(130, 1), "Spearman is tier 2");
        assertFalse(CompanionController.isJobTierWithinLimit(210, 1), "Wizard FP is tier 2");
        assertFalse(CompanionController.isJobTierWithinLimit(111, 1), "Crusader is tier 3");
        assertFalse(CompanionController.isJobTierWithinLimit(112, 1), "Hero is tier 4");
        assertFalse(CompanionController.isJobTierWithinLimit(1110, 1), "Dawn Warrior 2 is tier 2");
    }

    @Test
    void higherTierLimitAdmitsDeeperJobs() {
        assertTrue(CompanionController.isJobTierWithinLimit(110, 2));
        assertTrue(CompanionController.isJobTierWithinLimit(111, 3));
        assertTrue(CompanionController.isJobTierWithinLimit(112, 4));
        assertFalse(CompanionController.isJobTierWithinLimit(112, 3));
    }

    @Test
    void invalidJobIdsAreRejected() {
        assertFalse(CompanionController.isJobTierWithinLimit(0, 1), "Beginner");
        assertFalse(CompanionController.isJobTierWithinLimit(-1, 1));
        assertFalse(CompanionController.isJobTierWithinLimit(100, 0), "limit 0 rejects all");
    }

    // ------------------------------------------------------------------
    // P0-2 / P0-6: per-session cooldowns (never shared across companions)
    // ------------------------------------------------------------------

    @Test
    void attackCooldownIsPerSessionNotGlobal() {
        CompanionSession a = newSession(1, 2);
        CompanionSession b = newSession(3, 4);

        assertTrue(a.tryAttack(10_000), "first session's first attack is allowed");
        // A second, DIFFERENT session must not be blocked by the first one's
        // cooldown. The original code kept lastAttackAt on the shared
        // controller, capping all companions to one attack per interval.
        assertTrue(b.tryAttack(10_000), "the other session must not share the cooldown");
        assertFalse(a.tryAttack(10_000), "the same session IS on cooldown");
    }

    @Test
    void lootAndConsumeCooldownsAreIndependentPerSession() {
        CompanionSession a = newSession(1, 2);
        CompanionSession b = newSession(3, 4);
        assertTrue(a.tryLoot(10_000));
        assertTrue(b.tryLoot(10_000));
        assertTrue(a.tryConsume(10_000));
        assertTrue(b.tryConsume(10_000));
        assertFalse(a.tryLoot(10_000));
        assertFalse(a.tryConsume(10_000));
    }

    @Test
    void zeroIntervalMeansEveryCallPasses() {
        CompanionSession s = newSession(1, 2);
        assertTrue(s.tryAttack(0));
        assertTrue(s.tryAttack(0));
        assertTrue(s.tryLoot(0));
        assertTrue(s.tryLoot(0));
    }

    // ------------------------------------------------------------------
    // P1-1: ammunition classification uses the WZ predicates
    // ------------------------------------------------------------------

    @Test
    void basicBowArrowIsBowAmmoNotCrossbowAmmo() {
        assertTrue(CompanionCombatController.isAmmoOfKind(2060000,
                CompanionCombatProfile.AmmoKind.ARROW));
        assertFalse(CompanionCombatController.isAmmoOfKind(2060000,
                CompanionCombatProfile.AmmoKind.BOLT));
    }

    @Test
    void crossbowArrowIsNotAcceptedAsBowAmmo() {
        // 2061000 is "Arrow for Crossbow" (verified wz/String.wz/Consume.img.xml).
        // The original hardcoded list offered it to bow users.
        assertFalse(CompanionCombatController.isAmmoOfKind(2061000,
                CompanionCombatProfile.AmmoKind.ARROW),
                "crossbow arrows must not be consumed by a bow");
        assertTrue(CompanionCombatController.isAmmoOfKind(2061000,
                CompanionCombatProfile.AmmoKind.BOLT));
    }

    @Test
    void upgradedArrowsAreRecognised() {
        // The hardcoded list only contained the two starter arrows, so any
        // archer past the starter silently stopped attacking.
        assertTrue(CompanionCombatController.isAmmoOfKind(2060001,
                CompanionCombatProfile.AmmoKind.ARROW), "Bronze Arrow for Bow");
        assertTrue(CompanionCombatController.isAmmoOfKind(2061001,
                CompanionCombatProfile.AmmoKind.BOLT), "Bronze Arrow for Crossbow");
    }

    @Test
    void bulletsAndStarsAreRecognised() {
        assertTrue(CompanionCombatController.isAmmoOfKind(2330000,
                CompanionCombatProfile.AmmoKind.BULLET));
        assertTrue(CompanionCombatController.isAmmoOfKind(2070000,
                CompanionCombatProfile.AmmoKind.STAR));
        assertTrue(CompanionCombatController.isAmmoOfKind(2070013,
                CompanionCombatProfile.AmmoKind.STAR));
    }

    @Test
    void nonAmmoItemsAreRejected() {
        for (int itemId : new int[]{2000000, 4000000, 4010000, 1002000}) {
            assertFalse(CompanionCombatController.isAmmoOfKind(itemId,
                    CompanionCombatProfile.AmmoKind.ARROW), "item " + itemId);
            assertFalse(CompanionCombatController.isAmmoOfKind(itemId,
                    CompanionCombatProfile.AmmoKind.BULLET), "item " + itemId);
        }
        assertFalse(CompanionCombatController.isAmmoOfKind(2060000,
                CompanionCombatProfile.AmmoKind.NONE));
    }

    // ------------------------------------------------------------------
    // P1-3: scripted maps must not host companions
    // ------------------------------------------------------------------

    @Test
    void nullMapIsNeverScriptedAndNeverHosts() {
        assertFalse(CompanionMapPolicy.hasEntryScript(null));
        assertFalse(CompanionMapPolicy.canHost(null));
    }

    @Test
    void instancedMapsNeverHostCompanions() {
        // Even if an operator allowlisted one of these ids, the hard blocklist
        // must win: a companion there could satisfy a party-size check.
        for (int mapId : new int[]{910_010_000, 925_000_000, 960_000_000}) {
            assertFalse(CompanionMapPolicy.isBlocked(mapId) == false,
                    "map " + mapId + " must be blocked");
        }
    }

    // ------------------------------------------------------------------
    // Session state machine hardening (P0-3 / P0-5)
    // ------------------------------------------------------------------

    @Test
    void dismissIsIdempotentAndStateAware() {
        CompanionSession s = newSession(1, 2);
        s.compareAndSetState(CompanionSession.State.NEW, CompanionSession.State.ACTIVE);
        // A session that is not ACTIVE cannot be dismissed twice; the lifecycle
        // service now checks the CAS result instead of ignoring it.
        assertTrue(s.state() == CompanionSession.State.ACTIVE);
        assertTrue(s.compareAndSetState(CompanionSession.State.ACTIVE,
                CompanionSession.State.DISMISSING));
        assertFalse(s.compareAndSetState(CompanionSession.State.ACTIVE,
                CompanionSession.State.DISMISSING),
                "a second concurrent dismiss must not win the CAS");
    }

    @Test
    void saveFailedIsATerminalHoldingState() {
        CompanionSession s = newSession(1, 2);
        s.compareAndSetState(CompanionSession.State.NEW, CompanionSession.State.ACTIVE);
        s.compareAndSetState(CompanionSession.State.ACTIVE, CompanionSession.State.DISMISSING);
        assertTrue(s.compareAndSetState(CompanionSession.State.DISMISSING,
                CompanionSession.State.SAVE_FAILED));
        assertEquals(CompanionSession.State.SAVE_FAILED, s.state());
    }

    @Test
    void lockIsExposedAndReentrant() {
        CompanionSession s = newSession(1, 2);
        s.lock().lock();
        try {
            s.lock().lock();   // ReentrantLock(true) is reentrant
            try {
                assertTrue(true);
            } finally {
                s.lock().unlock();
            }
        } finally {
            s.lock().unlock();
        }
    }

    // ------------------------------------------------------------------
    // Mode parsing and allowlist helpers
    // ------------------------------------------------------------------

    @Test
    void lootDefaultStaysDisabledInConfig() {
        assertFalse(coop.config.CoopDefaults.companionLootEnabledDefault());
    }

    // ------------------------------------------------------------------
    // Second review round
    // ------------------------------------------------------------------

    @Test
    void consumeIntervalIsConfiguredAndSane() {
        // Without this gate a hurt companion drank up to two potions per second
        // (tick_ms 500) and drained its whole stack.
        int interval = coop.config.CoopDefaults.companionConsumeIntervalMs();
        assertTrue(interval >= 100, "consume interval must be clamped >= 100ms, got " + interval);
        assertTrue(interval <= 60_000, "consume interval must be clamped <= 60000ms");
    }

    @Test
    void consumeCooldownIsExercisedPerSession() {
        CompanionSession a = newSession(1, 2);
        CompanionSession b = newSession(3, 4);
        assertTrue(a.tryConsume(10_000));
        assertTrue(b.tryConsume(10_000), "sessions must not share the consume cooldown");
        assertFalse(a.tryConsume(10_000));
    }

    @Test
    void noblesseAndGmHaveNoCombatProfile() {
        // The tier formula admits 1000 (Noblesse) and 900 (GM) as "tier 1", so
        // bind MUST additionally require an actual combat profile.
        assertEquals(null, CompanionCombatProfile.forJob(1000), "Noblesse has no profile");
        assertEquals(null, CompanionCombatProfile.forJob(900), "GM has no profile");
        assertEquals(null, CompanionCombatProfile.forJob(0), "Beginner has no profile");
    }

    @Test
    void everyFirstJobFamilyHasACombatProfile() {
        for (int jobId : new int[]{100, 200, 300, 400, 500,
                1100, 1200, 1300, 1400, 1500, 2100, 2200}) {
            assertTrue(CompanionCombatProfile.forJob(jobId) != null,
                    "job " + jobId + " must have a combat profile");
        }
    }

    @Test
    void dismissWithoutACompanionObjectFailsAndHoldsTheSession() {
        // A dismiss that cannot resolve the Character must NOT report success:
        // nothing would be saved, the bot would stay attached to its map and
        // party, and the freed slot would allow loading the same DB row twice.
        CompanionSession s = newSession(1, 2);
        assertTrue(s.compareAndSetState(CompanionSession.State.NEW,
                CompanionSession.State.ACTIVE));

        CompanionLifecycleService.Result result =
                CompanionLifecycleService.getInstance().dismiss(s, null);

        assertFalse(result.success(),
                "dismissing an unreachable companion must fail");
        assertEquals(CompanionSession.State.SAVE_FAILED, s.state(),
                "the session must be held in SAVE_FAILED, not CLOSED");
    }

    @Test
    void aHeldSessionCannotBeDismissedAgainIntoSuccess() {
        CompanionSession s = newSession(1, 2);
        s.compareAndSetState(CompanionSession.State.NEW, CompanionSession.State.ACTIVE);
        CompanionLifecycleService.getInstance().dismiss(s, null);
        assertEquals(CompanionSession.State.SAVE_FAILED, s.state());

        // Every later attempt must keep failing rather than eventually reporting
        // success and releasing the slot with unsaved state.
        CompanionLifecycleService.Result again =
                CompanionLifecycleService.getInstance().dismiss(s, null);
        assertFalse(again.success());
        assertEquals(CompanionSession.State.SAVE_FAILED, s.state());
    }

    @Test
    void instancedMapRangesAreHardBlocked() {
        for (int mapId : new int[]{910_010_000, 925_000_000, 960_000_000}) {
            assertTrue(CompanionMapPolicy.isBlocked(mapId),
                    "map " + mapId + " must be on the hard blocklist");
        }
        for (int mapId : new int[]{100_000_001, 100_020_000}) {
            assertFalse(CompanionMapPolicy.isBlocked(mapId),
                    "ordinary map " + mapId + " must not be blocked");
        }
    }

    @Test
    void companionAllowedMapIdsIsNeverNull() {
        // Callers must be able to iterate the result unconditionally.
        List<Integer> ids = coop.config.CoopDefaults.companionAllowedMapIds();
        assertTrue(ids != null);
    }

    private CompanionSession newSession(int ownerId, int companionId) {
        return new CompanionSession(ownerId, companionId, 1, 0, 1,
                CompanionSession.Mode.PASSIVE, false);
    }
}
