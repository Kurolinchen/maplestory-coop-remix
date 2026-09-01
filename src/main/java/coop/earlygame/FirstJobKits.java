/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.earlygame;

import client.Character;
import client.inventory.manipulator.InventoryManipulator;
import coop.config.CoopDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Early Game Remix (coop 0.1b): data-driven first-job starter kits.
 *
 * <p>Upstream grants one weapon per class inside five NPC scripts, which
 * scatters balance across JavaScript and duplicates the Cygnus quest path.
 * This service reads {@code coop_first_job_kits} instead and is invoked from
 * the single code path every advancement flows through
 * ({@code Character.changeJob}).
 *
 * <p>Kit contents are a pure data change: inserting or editing rows in
 * {@code coop_first_job_kits} changes what fresh first jobs receive, with no
 * code change and no client patch.
 */
public final class FirstJobKits {
    private static final Logger log = LoggerFactory.getLogger(FirstJobKits.class);

    private static final FirstJobKits INSTANCE = new FirstJobKits();

    private final Map<Integer, List<KitEntry>> cache = new HashMap<>();
    private volatile Map<Integer, List<KitEntry>> published = Map.of();

    private FirstJobKits() {
    }

    public static FirstJobKits getInstance() {
        return INSTANCE;
    }

    /**
     * A single kit row. {@code jobid == 0} is the "all classes" bucket that is
     * granted in addition to the class-specific rows.
     */
    public record KitEntry(int jobId, int itemId, int quantity) {
    }

    /** Whether a job id is an advancement that receives a first-job kit. */
    public static boolean isFirstJob(int jobId) {
        return switch (jobId) {
            case 100, 200, 300, 400, 500,
                 1100, 1200, 1300, 1400, 1500,
                 2100 -> true;
            default -> false;
        };
    }

    /**
     * Grants the kit for the new job, if the feature is enabled and the job is
     * a first job. Never throws: a missing table or a full inventory must not
     * break the job advancement itself.
     */
    public void grantOnAdvancement(Character chr, int newJobId) {
        if (chr == null || !CoopDefaults.firstJobKitsEnabled()) {
            return;
        }
        if (!isFirstJob(newJobId)) {
            return;
        }
        List<KitEntry> entries = entriesFor(newJobId);
        if (entries.isEmpty()) {
            return;
        }
        int granted = 0;
        for (KitEntry entry : entries) {
            try {
                int quantity = Math.max(1, entry.quantity());
                short shortQuantity = (short) Math.min(quantity, Short.MAX_VALUE);
                if (InventoryManipulator.addById(chr.getClient(), entry.itemId(), shortQuantity)) {
                    granted++;
                } else {
                    log.warn("First-job kit item did not fit character={} item={}",
                            chr.getId(), entry.itemId());
                }
            } catch (RuntimeException e) {
                log.warn("First-job kit grant failed character={} item={}: {}",
                        chr.getId(), entry.itemId(), e.getMessage());
            }
        }
        log.info("First-job kit granted character={} job={} items={}/{}",
                chr.getId(), newJobId, granted, entries.size());
    }

    /**
     * All kit rows applying to this job: the shared bucket plus class rows.
     * Reads an immutable snapshot, so it is safe to call while another thread
     * reloads the table.
     */
    public List<KitEntry> entriesFor(int jobId) {
        ensureLoaded();
        List<KitEntry> result = new ArrayList<>();
        List<KitEntry> shared = published.get(0);
        if (shared != null) {
            result.addAll(shared);
        }
        if (jobId != 0) {
            List<KitEntry> specific = published.get(jobId);
            if (specific != null) {
                result.addAll(specific);
            }
        }
        return List.copyOf(result);
    }

    /** Drops the in-process cache so the next call re-reads the table. */
    public void reload() {
        synchronized (cache) {
            cache.clear();
            published = Map.of();
        }
    }

    private void ensureLoaded() {
        if (!published.isEmpty()) {
            return;
        }
        synchronized (cache) {
            if (!published.isEmpty()) {
                return;
            }
            loadFromDb();
            Map<Integer, List<KitEntry>> copy = new HashMap<>();
            for (Map.Entry<Integer, List<KitEntry>> e : cache.entrySet()) {
                copy.put(e.getKey(), List.copyOf(e.getValue()));
            }
            published = Map.copyOf(copy);
        }
    }

    private void loadFromDb() {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT jobid, itemid, quantity FROM coop_first_job_kits")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int jobId = rs.getInt("jobid");
                    int itemId = rs.getInt("itemid");
                    int quantity = rs.getInt("quantity");
                    if (itemId <= 0) {
                        continue;
                    }
                    cache.computeIfAbsent(jobId, k -> new ArrayList<>())
                            .add(new KitEntry(jobId, itemId, quantity));
                }
            }
        } catch (SQLException | RuntimeException e) {
            // Transient DB problems must not permanently disable kits: leaving
            // the snapshot empty means the next call retries the load.
            log.error("coop_first_job_kits: load failed; retrying on next grant", e);
            cache.clear();
        }
    }
}
