/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package server.maps;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice A.8 regression tests (audit fix B7).
 *
 * <p>MapleMap.getCurrentSpawnRate previously had a discontinuity at 3 players
 * (1 player -> 0.95 instead of the documented 0.90, and 3 players -> 0.85
 * instead of climbing above 1.0). The formula must now be:
 * <pre>
 *   n <= 0 -> 0.90 (defensive floor)
 *   1     -> 0.90
 *   2     -> 0.95
 *   3     -> 1.00
 *   ...
 *   6+    -> 1.15 (capped)
 * </pre>
 * The branch only matters when USE_ENABLE_FULL_RESPAWN is off; with full
 * respawn the formula is bypassed entirely.
 */
class SpawnRateFormulaTest {

    private double invokeFormula(int numPlayers) throws Exception {
        Method m = MapleMap.class.getDeclaredMethod("getCurrentSpawnRate", int.class);
        m.setAccessible(true);
        return (double) m.invoke(null, numPlayers);
    }

    @Test
    void zeroPlayersReturnsDefensiveFloor() throws Exception {
        double r = invokeFormula(0);
        assertEquals(0.90, r, 1e-9);
    }

    @Test
    void onePlayerReturnsDocumentedSoloFloor() throws Exception {
        double r = invokeFormula(1);
        assertEquals(0.90, r, 1e-9, "solo floor must be exactly 0.90");
    }

    @Test
    void twoPlayersIsAboveSoloFloor() throws Exception {
        double r = invokeFormula(2);
        assertEquals(0.95, r, 1e-9);
    }

    @Test
    void curveIsMonotoneAndBounded() throws Exception {
        double previous = -1.0;
        for (int n = 0; n <= 8; n++) {
            double r = invokeFormula(n);
            assertTrue(r >= previous, "curve must be non-decreasing, got " + r
                    + " after " + previous + " (n=" + n + ")");
            previous = r;
        }
        // cap at the documented 1.15 ceiling (6+ players)
        assertEquals(1.15, invokeFormula(6), 1e-9);
        assertEquals(1.15, invokeFormula(7), 1e-9);
    }

    @Test
    void formulaMethodExistsWithExpectedSignature() throws Exception {
        Method m = MapleMap.class.getDeclaredMethod("getCurrentSpawnRate", int.class);
        assertNotNull(m);
        assertEquals(double.class, m.getReturnType());
    }
}
