/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.earlygame;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeginnerStartTest {

    @Test
    void noviceJobsAreBeginnerJobs() {
        assertTrue(BeginnerStart.isBeginnerJob(0), "Beginner");
        assertTrue(BeginnerStart.isBeginnerJob(1000), "Noblesse");
        assertTrue(BeginnerStart.isBeginnerJob(2000), "Legend");
    }

    @Test
    void veteranAndClassJobsAreNotBeginnerJobs() {
        // Veteran creators already give the character a class, so they must not
        // receive the beginner SP bonus on top of their class kit.
        assertFalse(BeginnerStart.isBeginnerJob(100), "Warrior");
        assertFalse(BeginnerStart.isBeginnerJob(1100), "Dawn Warrior");
        assertFalse(BeginnerStart.isBeginnerJob(2100), "Aran");
        assertFalse(BeginnerStart.isBeginnerJob(2001), "Evan");
        assertFalse(BeginnerStart.isBeginnerJob(-1));
    }

    @Test
    void spBonusAppliesOnlyToBeginnerJobs() {
        assertEquals(3, BeginnerStart.spBonusFor(0, 3));
        assertEquals(3, BeginnerStart.spBonusFor(1000, 3));
        assertEquals(0, BeginnerStart.spBonusFor(100, 3), "a veteran must get nothing");
    }

    @Test
    void spBonusIsNeverNegative() {
        assertEquals(0, BeginnerStart.spBonusFor(0, -5));
        assertEquals(0, BeginnerStart.spBonusFor(0, 0));
        assertEquals(7, BeginnerStart.spBonusFor(0, 7));
    }
}
