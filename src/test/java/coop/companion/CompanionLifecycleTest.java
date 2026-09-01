/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.companion;

import client.Character;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;

/**
 * Slice B contract tests for the lifecycle result type and the job-tier policy.
 *
 * <p>Full spawn/dismiss behaviour needs a live world and map, so it is covered
 * by the manual playtest checklist; these tests pin the contracts that the
 * controller and the lifecycle service rely on.
 */
class CompanionLifecycleTest {

    @Test
    void resultFactoryShapes() {
        CompanionLifecycleService.Result ok = CompanionLifecycleService.Result.ok(null);
        assertTrue(ok.success());
        assertEquals(null, ok.reason());

        CompanionLifecycleService.Result bad =
                CompanionLifecycleService.Result.fail("party is full");
        assertFalse(bad.success());
        assertEquals("party is full", bad.reason());
        assertEquals(null, bad.companion());
    }

    @Test
    void controllerOutcomeShapes() {
        CompanionController.Outcome ok = CompanionController.Outcome.ok("done");
        assertTrue(ok.success());
        assertEquals("done", ok.message());

        CompanionController.Outcome bad = CompanionController.Outcome.fail("nope");
        assertFalse(bad.success());
        assertEquals("nope", bad.message());
    }

    @Test
    void bindingRecordCarriesAllFields() {
        CompanionBindingRepository.Binding b = new CompanionBindingRepository.Binding(
                1, 2, 3, 0, "PASSIVE", false);
        assertEquals(1, b.ownerCharacterId());
        assertEquals(2, b.companionCharacterId());
        assertEquals(3, b.accountId());
        assertEquals(0, b.world());
        assertEquals("PASSIVE", b.mode());
        assertFalse(b.lootEnabled());
    }

    @Test
    void ownershipRejectionCarriesReasonOnly() {
        CompanionBindingRepository.OwnershipCheckResult rejected =
                CompanionBindingRepository.OwnershipCheckResult.reject("different account");
        assertFalse(rejected.allowed());
        assertEquals("different account", rejected.reason());
        assertEquals(-1, rejected.world());
        assertEquals(-1, rejected.companionJob());
    }

    @Test
    void ownershipApprovalCarriesNamesAndJob() {
        CompanionBindingRepository.OwnershipCheckResult allowed =
                CompanionBindingRepository.OwnershipCheckResult.allow("Owner", "Bot", 0, 100);
        assertTrue(allowed.allowed());
        assertEquals("Owner", allowed.ownerName());
        assertEquals("Bot", allowed.companionName());
        assertEquals(0, allowed.world());
        assertEquals(100, allowed.companionJob());
    }

    @Test
    void sessionSnapshotSurvivesRepeatedCalls() {
        CompanionSession s = new CompanionSession(10, 11, 1, 0, 1,
                CompanionSession.Mode.PASSIVE, false);
        String first = s.snapshot();
        String second = s.snapshot();
        assertEquals(first, second, "snapshot must be a pure read of current state");
        assertNotNull(first);
    }

    @Test
    void modeEnumCoversExpectedValues() {
        // Slice D will wire these to actual behaviour; the MVP must at least
        // expose the modes the owner is expected to ask for.
        CompanionSession.Mode[] modes = CompanionSession.Mode.values();
        assertTrue(modes.length >= 5, "expected PASSIVE, FOLLOW, GRIND, SUPPORT, STAY");
        assertNotNull(CompanionSession.Mode.valueOf("PASSIVE"));
        assertNotNull(CompanionSession.Mode.valueOf("FOLLOW"));
        assertNotNull(CompanionSession.Mode.valueOf("GRIND"));
    }

    @Test
    void sessionRecordsFailuresIndependently() {
        CompanionSession s = new CompanionSession(20, 21, 1, 0, 1,
                CompanionSession.Mode.PASSIVE, false);
        assertEquals(0, s.consecutiveFailures());
        s.markTickCompleted();
        assertEquals(0, s.consecutiveFailures());
        s.recordTickFailure("a");
        assertEquals(1, s.consecutiveFailures());
        assertEquals("a", s.lastError());
        s.recordTickFailure("b");
        assertEquals(2, s.consecutiveFailures());
        s.markTickCompleted();
        assertEquals(0, s.consecutiveFailures(), "a successful tick resets the counter");
    }

    @Test
    void partyHpSynchronizationReceivesBeforePublishingOwnHp() {
        Character bot = mock(Character.class);

        CompanionLifecycleService.synchronizePartyHp(bot);

        org.mockito.InOrder order = inOrder(bot);
        order.verify(bot).receivePartyMemberHP();
        order.verify(bot).updatePartyMemberHP();
    }
}
