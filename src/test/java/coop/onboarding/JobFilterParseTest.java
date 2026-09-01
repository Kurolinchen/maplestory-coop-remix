/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.onboarding;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice A.6 regression tests (audit fix B6).
 *
 * <p>The class-aware hint loader must:
 * <ul>
 *   <li>treat blank/null {@code job_filter} as "applies to every job";</li>
 *   <li>parse the comma-separated {@code job_filter} into a job-id set;</li>
 *   <li>ignore malformed tokens without raising;</li>
 * </ul>
 */
class JobFilterParseTest {

    @Test
    void blankFilterMeansAppliesToAll() {
        Set<Integer> jobs = MilestoneHints.parseJobFilter(null);
        assertTrue(jobs.isEmpty());
        assertTrue(new MilestoneHints.HintEntry("x", 1, "t", jobs).appliesToJob(100));

        Set<Integer> jobs2 = MilestoneHints.parseJobFilter("");
        assertTrue(jobs2.isEmpty());

        Set<Integer> jobs3 = MilestoneHints.parseJobFilter("   ");
        assertTrue(jobs3.isEmpty());
    }

    @Test
    void commaSeparatedFilterParsesCleanly() {
        Set<Integer> jobs = MilestoneHints.parseJobFilter("100,1100,1110");
        assertEquals(3, jobs.size());
        assertTrue(jobs.contains(100));
        assertTrue(jobs.contains(1100));
        assertTrue(jobs.contains(1110));
    }

    @Test
    void malformedTokensAreIgnored() {
        Set<Integer> jobs = MilestoneHints.parseJobFilter("100,abc,200,xyz");
        assertEquals(2, jobs.size());
        assertTrue(jobs.contains(100));
        assertTrue(jobs.contains(200));
        assertFalse(jobs.contains(999));
    }

    @Test
    void hintEntryAppliesToMatchingJobOnly() {
        MilestoneHints.HintEntry warrior = new MilestoneHints.HintEntry(
                "second-job-warrior", 30, "Perion",
                Set.of(100, 1100, 1110, 1111, 1112));
        assertTrue(warrior.appliesToJob(100));
        assertTrue(warrior.appliesToJob(1100));
        assertFalse(warrior.appliesToJob(200), "Magician must not see the Warrior hint");

        MilestoneHints.HintEntry general = new MilestoneHints.HintEntry(
                "first-job-adventurer", 10, "any class", Set.of());
        for (int jobId : new int[]{100, 200, 300, 400, 500}) {
            assertTrue(general.appliesToJob(jobId), "general hint must reach job " + jobId);
        }
    }
}
