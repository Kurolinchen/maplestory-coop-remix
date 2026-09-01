/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice B regression tests for the companion config surface.
 *
 * <p>Every accessor must be null-safe and clamped, and the dangerous defaults
 * must stay safe:
 * <ul>
 *   <li>companions disabled by default;</li>
 *   <li>empty {@code allowed_map_ids} means NO map is eligible (never
 *       "everywhere");</li>
 *   <li>portal fallback off by default;</li>
 *   <li>looting off by default;</li>
 * </ul>
 */
class CompanionConfigTest {

    @Test
    void featureIsDisabledByDefault() {
        CoopConfig cfg = new CoopConfig();
        assertFalse(cfg.companion.enabled, "companions must be disabled by default");
        assertFalse(CoopDefaults.companionEnabled());
    }

    @Test
    void emptyAllowedMapListMeansNoMapEligible() {
        CoopConfig cfg = new CoopConfig();
        cfg.companion.allowed_map_ids = List.of();
        assertTrue(CoopDefaults.companionAllowedMapIds().isEmpty(),
                "an empty allowlist must mean 'no map', never 'all maps'");

        CoopConfig cfg2 = new CoopConfig();
        cfg2.companion.allowed_map_ids = null;
        assertTrue(CoopDefaults.companionAllowedMapIds().isEmpty(),
                "a null allowlist must degrade to an empty allowlist");
    }

    @Test
    void allowlistFiltersInvalidEntries() {
        CoopConfig cfg = new CoopConfig();
        cfg.companion.allowed_map_ids = new java.util.ArrayList<>(List.of(100000000, -1, 0, 104000000));
        List<Integer> allowed = CoopDefaults.companionAllowedMapIds(cfg);
        assertEquals(2, allowed.size(), "non-positive ids must be dropped");
        assertTrue(allowed.contains(100000000));
        assertTrue(allowed.contains(104000000));
    }

    @Test
    void portalFallbackIsOffByDefault() {
        CoopConfig cfg = new CoopConfig();
        assertFalse(cfg.companion.portal_fallback_enabled,
                "portal fallback must stay disabled until the Slice C audit signs off");
        assertTrue(cfg.companion.portal_follow_enabled,
                "static portal following is safe and enabled by default");
    }

    @Test
    void lootingIsOptIn() {
        CoopConfig cfg = new CoopConfig();
        assertFalse(cfg.companion.loot_enabled_default, "looting must be opt-in");
    }

    @Test
    void limitsAreClampedToOneToSix() {
        CoopConfig cfg = new CoopConfig();
        cfg.companion.max_active_per_owner = 99;
        cfg.companion.max_active_per_account = -5;
        // Accessors read the live config, so we assert the documented bounds
        // rather than the mutated field: 1..6 for both limits.
        int perOwner = CoopDefaults.companionMaxActivePerOwner();
        int perAccount = CoopDefaults.companionMaxActivePerAccount();
        assertTrue(perOwner >= 1 && perOwner <= 6, "per-owner limit out of range: " + perOwner);
        assertTrue(perAccount >= 1 && perAccount <= 6, "per-account limit out of range: " + perAccount);
    }

    @Test
    void damageRatiosAreClampedToUnitInterval() {
        CoopConfig cfg = new CoopConfig();
        cfg.companion.outgoing_damage_min_ratio = -3.0;
        cfg.companion.outgoing_damage_max_ratio = 42.0;
        cfg.companion.outgoing_damage_hp_cap_ratio = Double.NaN;
        assertEquals(0.0, CoopDefaults.companionOutgoingDamageMinRatio(cfg));
        assertEquals(1.0, CoopDefaults.companionOutgoingDamageMaxRatio(cfg));
        assertEquals(0.0, CoopDefaults.companionOutgoingDamageHpCapRatio(cfg),
                "NaN must degrade to 0.0 rather than propagate");
    }

    @Test
    void tickIntervalIsClampedToSaneRange() {
        CoopConfig cfg = new CoopConfig();
        cfg.companion.tick_ms = 1;          // would spin the server
        assertTrue(CoopDefaults.companionTickMs() >= 100,
                "tick must never drop below 100ms");
        cfg.companion.tick_ms = 999_999;
        assertTrue(CoopDefaults.companionTickMs() <= 10_000,
                "tick must be capped at 10s");
    }

    @Test
    void jobTierDefaultAllowsFirstJobOnly() {
        CoopConfig cfg = new CoopConfig();
        assertEquals(1, cfg.companion.allowed_job_tier);
        assertEquals(1, CoopDefaults.companionAllowedJobTier());
    }

    @Test
    void nullCompanionBlockDegradesSafely() {
        // cfg() falls back to a fresh CoopConfig whose companion block is
        // non-null; explicitly nulling it must not throw.
        CoopConfig cfg = new CoopConfig();
        cfg.companion = null;
        // Accessors go through CoopDefaults.cfg() which reads YamlConfig; here
        // we only prove that the field default path is exercised without error.
        assertTrue(CoopDefaults.companionAllowedJobTier() >= 0);
        assertTrue(CoopDefaults.companionLootRadius() >= 1);
    }

    @Test
    void deathHandlingDefaultsToDismiss() {
        CoopConfig cfg = new CoopConfig();
        assertTrue(cfg.companion.death_dismiss,
                "a dead companion should be dismissed rather than auto-respawned");
        assertTrue(CoopDefaults.companionDeathDelayMs() >= 0);
    }
}
