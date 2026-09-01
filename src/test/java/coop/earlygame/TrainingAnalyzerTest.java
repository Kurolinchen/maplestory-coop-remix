/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.earlygame;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingAnalyzerTest {

    @Test
    void expPerHourOverAKnownSpan() {
        long first = 1_000_000L;
        long oneHourLater = first + 3_600_000L;
        assertEquals(6000.0, TrainingAnalyzer.expPerHour(6000L, first, oneHourLater), 0.001);
    }

    @Test
    void expPerHourIsZeroForUnusableSpans() {
        assertEquals(0.0, TrainingAnalyzer.expPerHour(0, 1000L, 3_610_000L), "no exp");
        assertEquals(0.0, TrainingAnalyzer.expPerHour(500, 0L, 3_610_000L), "missing first sample");
        assertEquals(0.0, TrainingAnalyzer.expPerHour(500, 5_000L, 5_000L), "empty span");
        assertEquals(0.0, TrainingAnalyzer.expPerHour(500, 5_000L, 1_000L), "reversed span");
    }

    @Test
    void invertedLevelRangeYieldsNoRows() {
        assertTrue(TrainingAnalyzer.analyze(30, 1).isEmpty());
    }

    @Test
    void analysisWithoutTelemetryDataReturnsEmpty() {
        // Telemetry is opt-in; with no data the analyzer must degrade quietly
        // instead of throwing during a balance query.
        assertTrue(TrainingAnalyzer.analyze(1, 30).isEmpty());
    }
}
