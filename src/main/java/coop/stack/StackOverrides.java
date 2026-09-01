/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/

package coop.stack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Server-side stack size overrides (Milestone 0.1). Table {@code coop_stack_overrides}
 * (db/extensions/coop-1010) overrides WZ {@code info/slotMax} before it is consulted;
 * throwing-star/bullet mastery bonuses still apply on top.
 */
public class StackOverrides {
    private static final Logger log = LoggerFactory.getLogger(StackOverrides.class);

    private static final StackOverrides EMPTY = new StackOverrides(Map.of());

    private static volatile StackOverrides instance;

    private final Map<Integer, Short> overrides;

    public StackOverrides(Map<Integer, Short> overrides) {
        this.overrides = Map.copyOf(overrides);
    }

    public Optional<Short> maxPerSlot(int itemId) {
        return Optional.ofNullable(overrides.get(itemId));
    }

    public int size() {
        return overrides.size();
    }

    /**
     * Lazy singleton. A DB failure caches {@link #EMPTY} (no hot-path re-query/log spam);
     * in practice unreachable, since migration failure already aborts server startup.
     */
    public static StackOverrides getInstance() {
        StackOverrides local = instance;
        if (local == null) {
            synchronized (StackOverrides.class) {
                local = instance;
                if (local == null) {
                    local = loadFromDb();
                    if (local == null) {
                        local = EMPTY;
                    }
                    instance = local;
                }
            }
        }
        return local;
    }

    static StackOverrides loadFromDb() {
        Map<Integer, Short> loaded = new HashMap<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT item_id, max_per_slot FROM coop_stack_overrides")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    short maxPerSlot = rs.getShort("max_per_slot");
                    if (maxPerSlot < 1) {
                        log.warn("coop_stack_overrides: ignoring invalid max_per_slot {} for item {}",
                                maxPerSlot, rs.getInt("item_id"));
                        continue;
                    }
                    loaded.put(rs.getInt("item_id"), maxPerSlot);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load coop_stack_overrides; falling back to WZ slotMax values", e);
            return null;
        }
        log.info("Loaded {} coop stack override(s)", loaded.size());
        return new StackOverrides(loaded);
    }
}
