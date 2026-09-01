/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.earlygame;

import client.Job;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the job-tier rule behind the first-job kits.
 *
 * <p>Getting this wrong would either skip the kit on every real first job or
 * hand it out again on every second job advancement, so the rule is pinned
 * here rather than only covered by a manual test.
 */
class FirstJobKitsTest {
    private static final Set<Job> ELIGIBLE_JOBS = Set.of(
            Job.WARRIOR, Job.MAGICIAN, Job.BOWMAN, Job.THIEF, Job.PIRATE,
            Job.DAWNWARRIOR1, Job.BLAZEWIZARD1, Job.WINDARCHER1,
            Job.NIGHTWALKER1, Job.THUNDERBREAKER1, Job.ARAN1);

    @Test
    void everySupportedFirstJobIsEligible() {
        for (Job job : ELIGIBLE_JOBS) {
            assertTrue(FirstJobKits.isFirstJob(job.getId()), job.name());
        }
    }

    @Test
    void everyOtherDefinedJobIsIneligible() {
        for (Job job : Job.values()) {
            if (!ELIGIBLE_JOBS.contains(job)) {
                assertFalse(FirstJobKits.isFirstJob(job.getId()), job.name());
            }
        }
    }

    @Test
    void arithmeticFalsePositivesAndInvalidJobsGetNoKit() {
        for (int jobId : new int[]{-1, 600, 700, 800, 900, 1600, 1700, 1800, 1900, 2200, 2300}) {
            assertFalse(FirstJobKits.isFirstJob(jobId), "job " + jobId);
        }
    }

    @Test
    void kitEntryCarriesAllFields() {
        FirstJobKits.KitEntry entry = new FirstJobKits.KitEntry(300, 2060000, 2000);
        assertEquals(300, entry.jobId());
        assertEquals(2060000, entry.itemId());
        assertEquals(2000, entry.quantity());
    }

    @Test
    void migrationsSeedOnlySharedOrEligibleJobRows() throws Exception {
        for (String migration : new String[]{
                "coop-1211-first-job-kits-seed.xml",
                "coop-1212-first-job-kits-cygnus-ammo.xml"}) {
            for (SeedRow row : readSeedRows(migration).values()) {
                assertTrue(row.jobId() == 0 || FirstJobKits.isFirstJob(row.jobId()),
                        migration + " seeds ineligible job " + row.jobId());
            }
        }
    }

    @Test
    void supplementalCygnusAmmoMatchesRangedJobs() throws Exception {
        Map<Integer, SeedRow> rows = readSeedRows("coop-1212-first-job-kits-cygnus-ammo.xml");

        assertEquals(Map.of(
                1300, new SeedRow(1300, 2060000, 2000),
                1400, new SeedRow(1400, 2070000, 1000)), rows);
        assertFalse(rows.containsKey(1500), "Thunder Breaker is melee and must not receive ammo");
    }

    private static Map<Integer, SeedRow> readSeedRows(String fileName) throws Exception {
        Path path = Path.of("src/main/resources/db/extensions", fileName);
        NodeList inserts = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(path.toFile()).getElementsByTagName("insert");
        Map<Integer, SeedRow> rows = new HashMap<>();
        for (int i = 0; i < inserts.getLength(); i++) {
            NodeList columns = ((Element) inserts.item(i)).getElementsByTagName("column");
            Map<String, Integer> values = new HashMap<>();
            for (int j = 0; j < columns.getLength(); j++) {
                Element column = (Element) columns.item(j);
                if (column.hasAttribute("valueNumeric")) {
                    values.put(column.getAttribute("name"),
                            Integer.parseInt(column.getAttribute("valueNumeric")));
                }
            }
            SeedRow row = new SeedRow(values.get("jobid"), values.get("itemid"), values.get("quantity"));
            rows.put(row.jobId(), row);
        }
        return rows;
    }

    private record SeedRow(int jobId, int itemId, int quantity) {
    }
}
