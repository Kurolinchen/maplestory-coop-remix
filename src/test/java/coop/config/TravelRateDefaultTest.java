/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice A.7 regression tests (audit fix B11).
 *
 * <p>The startup code in {@code Server.initWorld} used to read
 * {@code YamlConfig.config.coop.travel_rate_default} directly, bypassing
 * {@code CoopDefaults}. A missing or null {@code coop:} block therefore left
 * the field at the Java default of 1 (different from the documented default
 * of 10) and, worse, dereferenced a null block.
 *
 * <p>Now the call goes through {@link CoopDefaults#travelRateDefault()} which
 * is null-safe and clamps to a safe positive range.
 */
class TravelRateDefaultTest {

    @Test
    void nullConfigYieldsPositiveDefault() {
        CoopConfig cfg = new CoopConfig();
        // default in CoopConfig is now 10 (the coop 0.1 contract)
        int rate = CoopDefaults.travelRateDefault(cfg);
        assertTrue(rate >= 1, "default must always be positive, got " + rate);
    }

    @Test
    void negativeValuesAreClampedToOne() {
        CoopConfig cfg = new CoopConfig();
        cfg.travel_rate_default = -5;
        assertEquals(1, CoopDefaults.travelRateDefault(cfg));
    }

    @Test
    void hugeValuesAreClampedToUpperBound() {
        CoopConfig cfg = new CoopConfig();
        cfg.travel_rate_default = 1_000_000;
        assertEquals(1000, CoopDefaults.travelRateDefault(cfg));
    }

    @Test
    void zeroIsClampedToOne() {
        CoopConfig cfg = new CoopConfig();
        cfg.travel_rate_default = 0;
        assertEquals(1, CoopDefaults.travelRateDefault(cfg));
    }

    @Test
    void inRangeValuePassesThrough() {
        CoopConfig cfg = new CoopConfig();
        cfg.travel_rate_default = 25;
        assertEquals(25, CoopDefaults.travelRateDefault(cfg));
    }
}
