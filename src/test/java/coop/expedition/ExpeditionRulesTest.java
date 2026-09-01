package coop.expedition;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExpeditionRulesTest {

    @Test
    void vanillaMinimumAppliesWithoutSoloFlagOrOverride() {
        assertEquals(6, ExpeditionRules.effectiveMinSize("ZAKUM", 6, false, Map.of()));
        assertEquals(3, ExpeditionRules.effectiveMinSize("BALROG_EASY", 3, false, null));
    }

    @Test
    void soloFlagLowersMinimumToOne() {
        assertEquals(1, ExpeditionRules.effectiveMinSize("ZAKUM", 6, true, Map.of()));
        assertEquals(1, ExpeditionRules.effectiveMinSize("HORNTAIL", 6, true, null));
    }

    @Test
    void perTypeOverrideWinsButNeverRaisesAboveVanilla() {
        assertEquals(3, ExpeditionRules.effectiveMinSize("ZAKUM", 6, false, Map.of("ZAKUM", 3)));
        // override higher than the vanilla minimum is capped at the vanilla value
        assertEquals(6, ExpeditionRules.effectiveMinSize("ZAKUM", 6, false, Map.of("ZAKUM", 10)));
        // override applies even when the solo flag is off
        assertEquals(2, ExpeditionRules.effectiveMinSize("HORNTAIL", 6, false, Map.of("HORNTAIL", 2)));
    }

    @Test
    void soloFlagStillAppliesWhenOtherTypesAreOverridden() {
        assertEquals(1, ExpeditionRules.effectiveMinSize("PINKBEAN", 6, true, Map.of("ZAKUM", 3)));
    }

    @Test
    void invalidOverridesAreIgnored() {
        assertEquals(6, ExpeditionRules.effectiveMinSize("ZAKUM", 6, false, Map.of("ZAKUM", 0)));
        assertEquals(6, ExpeditionRules.effectiveMinSize("ZAKUM", 6, false, Map.of("ZAKUM", -1)));
    }

    @Test
    void liveConfigYieldsSoloMinimums() {
        // config.yaml ships USE_ENABLE_SOLO_EXPEDITIONS=true since milestone 0.1 (DECISIONS D8)
        assertEquals(1, ExpeditionRules.effectiveMinSize(server.expeditions.ExpeditionType.ZAKUM, 6));
        assertEquals(1, ExpeditionRules.effectiveMinSize(server.expeditions.ExpeditionType.HORNTAIL, 6));
    }
}
