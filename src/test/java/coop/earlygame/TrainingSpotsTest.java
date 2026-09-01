/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.earlygame;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import provider.DataProvider;
import provider.wz.XMLWZFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingSpotsTest {

    @TempDir
    private Path wzRoot;

    @Test
    void parsesPaddedMapFilenames() {
        assertEquals(20000, TrainingSpots.parseMapId("000020000.img"));
        assertEquals(100020000, TrainingSpots.parseMapId("100020000.img"));
        assertEquals(0, TrainingSpots.parseMapId("000000000.img"));
        assertEquals(7, TrainingSpots.parseMapId("7.img"));
    }

    @Test
    void rejectsNonNumericOrOversizedNames() {
        assertNull(TrainingSpots.parseMapId(null));
        assertNull(TrainingSpots.parseMapId(""));
        assertNull(TrainingSpots.parseMapId("Map.img"));
        assertNull(TrainingSpots.parseMapId("12345678901.img"), "too long for an int");
    }

    @Test
    void queryGuardsRejectImpossibleInput() {
        TrainingSpots spots = TrainingSpots.getInstance();
        assertTrue(spots.spotsForLevel(0, 10, 5).isEmpty(), "level 0 is not a player level");
        assertTrue(spots.spotsForLevel(-3, 10, 5).isEmpty());
        assertTrue(spots.spotsForLevel(20, 10, 0).isEmpty(), "no results requested");
    }

    @Test
    void walksTheMapSubtreeRecursively() throws IOException {
        // Regression: Map.wz is NOT flat. A non-recursive walk over
        // root.getFiles() only finds Effect.img / MapHelper.img / Physics.img,
        // so the index stayed empty and @training always answered
        // "no training spot found".
        writeMap(wzRoot, "Map0", "000020000", """
                <imgdir name="info"><int name="town" value="0"/></imgdir>
                <imgdir name="life">
                  <imgdir name="0"><string name="type" value="m"/><string name="id" value="100"/></imgdir>
                  <imgdir name="1"><string name="type" value="m"/><string name="id" value="100"/></imgdir>
                </imgdir>
                """);

        TrainingSpots spots = newSpots();
        spots.buildSynchronously(provider(), mobId -> 20);

        assertEquals(1, spots.indexedMapCount());
        assertTrue(spots.isReady());
        List<TrainingSpots.Spot> found = spots.spotsForLevel(20, 5, 10);
        assertEquals(1, found.size());
        assertEquals(20000, found.get(0).mapId());
        assertEquals(100, found.get(0).mobId());
        assertEquals(20, found.get(0).mobLevel());
        assertEquals(2, found.get(0).mobCount());
    }

    @Test
    void townsAndScriptedFieldsAreNotRecommended() throws IOException {
        writeMap(wzRoot, "Map0", "000010000", """
                <imgdir name="info"><int name="town" value="1"/></imgdir>
                <imgdir name="life">
                  <imgdir name="0"><string name="type" value="m"/><string name="id" value="100"/></imgdir>
                </imgdir>
                """);
        writeMap(wzRoot, "Map0", "000030000", """
                <imgdir name="info"><string name="onUserEnter" value="someScript"/></imgdir>
                <imgdir name="life">
                  <imgdir name="0"><string name="type" value="m"/><string name="id" value="100"/></imgdir>
                </imgdir>
                """);

        TrainingSpots spots = newSpots();
        spots.buildSynchronously(provider(), mobId -> 20);

        assertEquals(0, spots.indexedMapCount(),
                "towns and scripted fields must never be recommended");
    }

    @Test
    void numericEntryScriptUsesRuntimeScriptAvailability() throws IOException {
        writeMap(wzRoot, "Map1", "100020000", """
                <imgdir name="info"><string name="onUserEnter" value="100020000"/></imgdir>
                <imgdir name="life">
                  <imgdir name="0"><string name="type" value="m"/><string name="id" value="100"/></imgdir>
                </imgdir>
                """);
        writeMap(wzRoot, "Map2", "200090000", """
                <imgdir name="info"><string name="onUserEnter" value="200090000"/></imgdir>
                <imgdir name="life">
                  <imgdir name="0"><string name="type" value="m"/><string name="id" value="100"/></imgdir>
                </imgdir>
                """);

        TrainingSpots spots = newSpots();
        spots.buildSynchronously(provider(), mobId -> 20);

        List<TrainingSpots.Spot> found = spots.spotsForLevel(20, 0, 10);
        assertEquals(1, found.size());
        assertEquals(100_020_000, found.getFirst().mapId(),
                "an absent numeric script is only a WZ fallback placeholder");
    }

    @Test
    void mapsWithoutMonstersAreSkipped() throws IOException {
        writeMap(wzRoot, "Map0", "000040000", """
                <imgdir name="info"><int name="town" value="0"/></imgdir>
                """);

        TrainingSpots spots = newSpots();
        spots.buildSynchronously(provider(), mobId -> 20);

        assertEquals(0, spots.indexedMapCount());
    }

    @Test
    void levelOutsideDeltaIsNotRecommended() throws IOException {
        writeMap(wzRoot, "Map0", "000020000", """
                <imgdir name="info"><int name="town" value="0"/></imgdir>
                <imgdir name="life">
                  <imgdir name="0"><string name="type" value="m"/><string name="id" value="100"/></imgdir>
                </imgdir>
                """);

        TrainingSpots spots = newSpots();
        spots.buildSynchronously(provider(), mobId -> 50);

        assertTrue(spots.spotsForLevel(10, 5, 10).isEmpty(),
                "a level-50 map must not be recommended for level 10");
        assertFalse(spots.spotsForLevel(50, 5, 10).isEmpty());
    }

    private static TrainingSpots newSpots() {
        TrainingSpots spots = TrainingSpots.getInstance();
        spots.reload();
        return spots;
    }

    private DataProvider provider() {
        return new XMLWZFile(wzRoot.resolve("Map.wz"));
    }

    private static void writeMap(Path root, String area, String mapName, String body)
            throws IOException {
        Path dir = root.resolve("Map.wz").resolve("Map").resolve(area);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(mapName + ".img.xml"),
                "<imgdir name=\"" + mapName + ".img\">" + body + "</imgdir>");
    }
}
