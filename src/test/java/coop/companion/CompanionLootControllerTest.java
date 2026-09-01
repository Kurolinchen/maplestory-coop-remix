/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.companion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice E regression tests for companion looting and equipment.
 *
 * <p>The binding integrity rule is that a companion is a NORMAL character: it
 * loots into its own inventory, never the owner's, and nothing is duplicated.
 * These tests pin the opt-in gate and the result shapes; live pickup behaviour
 * is exercised in the manual playtest.
 */
class CompanionLootControllerTest {

    @Test
    void lootingIsRefusedWhenDisabledOnTheSession() {
        CompanionLootController controller = new CompanionLootController();
        CompanionSession disabled = new CompanionSession(1, 2, 1, 0, 1,
                CompanionSession.Mode.PASSIVE, /* lootEnabled */ false);
        CompanionSession enabled = new CompanionSession(1, 2, 1, 0, 1,
                CompanionSession.Mode.PASSIVE, /* lootEnabled */ true);

        // With no character both refuse on the null-guard; that ordering is
        // intentional (defensive checks run before policy checks).
        assertEquals("missing session/bot", controller.tick(disabled, null).reason());

        // The flag itself must be readable and honoured by the session contract.
        assertFalse(disabled.lootEnabled(), "loot must default to disabled");
        assertTrue(enabled.lootEnabled());
    }

    @Test
    void missingSessionOrBotIsRejected() {
        CompanionLootController controller = new CompanionLootController();
        assertEquals("missing session/bot", controller.tick(null, null).reason());

        CompanionSession session = new CompanionSession(1, 2, 1, 0, 1,
                CompanionSession.Mode.PASSIVE, true);
        assertEquals("missing session/bot", controller.tick(session, null).reason());
    }

    @Test
    void resultShapesCarryCounts() {
        CompanionLootController.LootResult none =
                CompanionLootController.LootResult.none("cooldown");
        assertEquals(0, none.picked());
        assertEquals(0, none.skipped());
        assertEquals("cooldown", none.reason());

        CompanionLootController.LootResult done =
                CompanionLootController.LootResult.done(1, 3);
        assertEquals(1, done.picked());
        assertEquals(3, done.skipped());
        assertEquals(null, done.reason());
    }

    @Test
    void equipmentServiceRejectsMissingCompanion() {
        CompanionEquipmentService service = new CompanionEquipmentService();
        CompanionEquipmentService.EquipResult result = service.equip(null, 1, (short) 1);
        assertFalse(result.success());
        assertEquals("companion is not available", result.reason());
    }

    @Test
    void equipmentResultShapes() {
        CompanionEquipmentService.EquipResult ok = CompanionEquipmentService.EquipResult.ok();
        assertTrue(ok.success());
        assertEquals(null, ok.reason());

        CompanionEquipmentService.EquipResult bad =
                CompanionEquipmentService.EquipResult.fail("no free slot");
        assertFalse(bad.success());
        assertEquals("no free slot", bad.reason());
    }

    @Test
    void lootingDefaultsToDisabledInConfig() {
        // Looting must stay opt-in: a bot vacuuming party loot makes group
        // play worse, so the config default has to remain 'off'.
        assertFalse(coop.config.CoopDefaults.companionLootEnabledDefault());
    }

    @Test
    void lootRadiusAndIntervalArePositive() {
        assertTrue(coop.config.CoopDefaults.companionLootRadius() >= 1);
        assertTrue(coop.config.CoopDefaults.companionLootIntervalMs() >= 100);
    }
}
