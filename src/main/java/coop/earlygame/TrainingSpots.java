/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.earlygame;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataDirectoryEntry;
import provider.DataFileEntry;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import scripting.map.MapScriptManager;
import server.life.LifeFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * Early Game Remix (coop 0.1b): where to train at a given level.
 *
 * <p>Builds a lazy index of {@code mapId -> dominant monster level} by walking
 * the map WZ once. The index is built on first use and published as an
 * immutable snapshot, so readers never see a half-built map.
 *
 * <p>Only the WZ {@code life} block is read, and only monster entries
 * ({@code type == "m"}). Monster levels come from the existing
 * {@link LifeFactory#getMonsterLevel(int)} so there is a single source of
 * truth for mob level.
 */
public final class TrainingSpots {
    private static final Logger log = LoggerFactory.getLogger(TrainingSpots.class);

    /** Map ids below this are leftovers/test fields, not real hunting grounds. */
    private static final int MIN_REAL_MAP_ID = 10_000;

    private static final TrainingSpots INSTANCE = new TrainingSpots();

    private volatile Map<Integer, Spot> index = Map.of();
    private final java.util.concurrent.atomic.AtomicBoolean building =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile boolean ready = false;

    private TrainingSpots() {
    }

    /**
     * Whether the index is available. The first query triggers an asynchronous
     * build, because parsing the whole map WZ takes seconds and must never
     * block the packet thread that executed the command.
     */
    public boolean isReady() {
        return ready;
    }

    public static TrainingSpots getInstance() {
        return INSTANCE;
    }

    /** One index entry: a map and the level of the monsters that live there. */
    public record Spot(int mapId, int mobId, int mobLevel, int mobCount) {
    }

    /**
     * Recommended spots for a player level, closest level first.
     *
     * <p>Pure read over the immutable index: a spot qualifies when its monster
     * level is within {@code levelDelta} of the requested level.
     */
    public List<Spot> spotsForLevel(int level, int levelDelta, int maxResults) {
        if (level <= 0 || maxResults <= 0) {
            return List.of();
        }
        Map<Integer, Spot> snapshot = ensureLoaded();
        int delta = Math.max(0, levelDelta);
        List<Spot> matching = new ArrayList<>();
        for (Spot spot : snapshot.values()) {
            if (Math.abs(spot.mobLevel() - level) <= delta) {
                matching.add(spot);
            }
        }
        matching.sort(Comparator
                .comparingInt((Spot s) -> Math.abs(s.mobLevel() - level))
                .thenComparingInt(Spot::mapId));
        if (matching.size() > maxResults) {
            return List.copyOf(matching.subList(0, maxResults));
        }
        return List.copyOf(matching);
    }

    /** Number of indexed maps; exposed for diagnostics and tests. */
    public int indexedMapCount() {
        return ensureLoaded().size();
    }

    /** Drops the index so the next call rebuilds it. Test/diagnostic helper. */
    public void reload() {
        synchronized (this) {
            index = Map.of();
            ready = false;
            building.set(false);
        }
    }

    private Map<Integer, Spot> ensureLoaded() {
        Map<Integer, Spot> snapshot = index;
        // Gate on ready, not on emptiness: a legitimately empty index (no
        // matching maps) must not re-parse the whole WZ on every query.
        if (ready) {
            return snapshot;
        }
        // Building takes seconds; do it on a background thread so the first
        // player who types @training does not stall the netty worker.
        if (building.compareAndSet(false, true)) {
            Thread worker = new Thread(this::buildAndPublish);
            worker.setName("coop-training-index");
            worker.setDaemon(true);
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        }
        return index;
    }

    private void buildAndPublish() {
        try {
            long started = System.currentTimeMillis();
            index = Map.copyOf(buildIndex());
            ready = true;
            log.info("Training spot index built: {} maps in {} ms",
                    index.size(), System.currentTimeMillis() - started);
        } catch (RuntimeException e) {
            log.error("Training spot index build failed", e);
        } finally {
            building.set(false);
        }
    }

    /**
     * Synchronous build for tests, which need a deterministic result.
     *
     * <p>Takes the provider explicitly because {@code WZFiles.DIRECTORY} is a
     * static field: another test that sets the {@code wz-path} system property
     * can otherwise make this resolve game data inside a temporary directory.
     */
    void buildSynchronously(DataProvider provider, IntUnaryOperator mobLevelLookup) {
        synchronized (this) {
            index = Map.copyOf(buildIndexFrom(provider, mobLevelLookup));
            ready = true;
        }
    }

    private Map<Integer, Spot> buildIndex() {
        return buildIndexFrom(DataProviderFactory.getDataProvider(WZFiles.MAP),
                LifeFactory::getMonsterLevel);
    }

    /**
     * Builds the index from one provider.
     *
     * <p>The provider and the monster-level lookup are parameters so the walk
     * can be tested against a small fixture: {@code WZFiles.DIRECTORY} and
     * {@code LifeFactory} both keep static state that other tests can poison
     * through the {@code wz-path} system property.
     */
    Map<Integer, Spot> buildIndexFrom(DataProvider mapProvider,
                                      IntUnaryOperator mobLevelLookup) {
        Map<Integer, Spot> built = new HashMap<>();
        DataDirectoryEntry root = mapProvider.getRoot();
        if (root == null) {
            log.warn("Training spot index: map WZ root unavailable");
            return built;
        }
        // Map.wz is NOT flat: getFiles() on the root only yields Effect.img /
        // MapHelper.img / Physics.img. The maps live in the "Map" subdirectory
        // as Map/Map0..Map9/<9-digit>.img (see MapFactory.getMapName), so the
        // walk has to be recursive - and DataProvider.getData() needs the full
        // path relative to the WZ root, not just the file name.
        List<String> paths = new ArrayList<>();
        collectMapPaths(root, "", paths, 0);
        for (String path : paths) {
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            Integer mapId = parseMapId(fileName);
            if (mapId == null) {
                continue;
            }
            try {
                addMap(built, mapProvider, path, mapId, mobLevelLookup);
            } catch (RuntimeException e) {
                log.debug("Training spot index: skipping map {}: {}", mapId, e.getMessage());
            }
        }
        return built;
    }

    private void addMap(Map<Integer, Spot> built, DataProvider provider, String fileName,
                        int mapId, IntUnaryOperator mobLevelLookup) {
        if (mapId < MIN_REAL_MAP_ID) {
            return;
        }
        Data mapData = provider.getData(fileName);
        if (mapData == null) {
            return;
        }
        Data info = mapData.getChildByPath("info");
        if (info != null) {
            // Never recommend a map the player cannot simply walk into: towns,
            // scripted fields and instanced content are all unsuitable.
            if (DataTool.getInt("town", info, 0) != 0) {
                return;
            }
            if (hasEntryScript(info, mapId)) {
                return;
            }
        }
        Data life = mapData.getChildByPath("life");
        if (life == null) {
            return;
        }
        Map<Integer, Integer> mobCounts = new HashMap<>();
        for (Data entry : life.getChildren()) {
            if (!"m".equals(DataTool.getString("type", entry, ""))) {
                continue;
            }
            int mobId = DataTool.getInt("id", entry, -1);
            if (mobId > 0) {
                mobCounts.merge(mobId, 1, Integer::sum);
            }
        }
        if (mobCounts.isEmpty()) {
            return;
        }
        int dominantMob = -1;
        int dominantCount = -1;
        for (Map.Entry<Integer, Integer> e : mobCounts.entrySet()) {
            if (e.getValue() > dominantCount) {
                dominantCount = e.getValue();
                dominantMob = e.getKey();
            }
        }
        int mobLevel = mobLevelLookup.applyAsInt(dominantMob);
        if (mobLevel <= 0) {
            return;
        }
        built.put(mapId, new Spot(mapId, dominantMob, mobLevel, dominantCount));
    }

    /** Uses the runtime entry-script policy shared with companion map checks. */
    private static boolean hasEntryScript(Data info, int mapId) {
        return MapScriptManager.getInstance().hasEntryScript(
                mapId,
                DataTool.getString("onUserEnter", info, null),
                DataTool.getString("onFirstUserEnter", info, null));
    }

    /**
     * Collects map image paths recursively, relative to the WZ root. Only the
     * {@code Map} subtree is followed, so the large {@code Obj}/{@code Back}/
     * {@code Tile} branches are never touched.
     */
    private static void collectMapPaths(DataDirectoryEntry dir, String currentPath,
                                        List<String> out, int depth) {
        if (dir == null || depth > 4) {
            return;
        }
        boolean isRoot = depth == 0;
        if (!isRoot) {
            for (DataFileEntry file : dir.getFiles()) {
                out.add(currentPath.isEmpty() ? file.getName() : currentPath + "/" + file.getName());
            }
        }
        for (DataDirectoryEntry sub : dir.getSubdirectories()) {
            if (isRoot && !"Map".equals(sub.getName())) {
                continue;
            }
            String nextPath = isRoot || currentPath.isEmpty()
                    ? sub.getName() : currentPath + "/" + sub.getName();
            collectMapPaths(sub, nextPath, out, depth + 1);
        }
    }

    /** {@code 000020000.img} -> 20000. Returns null when unparsable. */
    static Integer parseMapId(String fileName) {
        if (fileName == null) {
            return null;
        }
        String base = fileName;
        int dot = base.indexOf('.');
        if (dot >= 0) {
            base = base.substring(0, dot);
        }
        if (base.isEmpty() || base.length() > 10) {
            return null;
        }
        for (int i = 0; i < base.length(); i++) {
            if (!Character.isDigit(base.charAt(i))) {
                return null;
            }
        }
        try {
            return Integer.valueOf(base);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
