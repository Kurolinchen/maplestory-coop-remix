/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.onboarding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-character repository for "hint already seen" markers (Slice A.1, audit fix B1).
 * Replaces the previous {@code Character.dataString} concatenation, which could
 * overflow the upstream {@code VARCHAR(64)} column and fail the entire character
 * save transaction once enough milestones had been crossed.
 *
 * <p>Reads are lazy + cached per character-id for the lifetime of the process;
 * writes are appended under the caller's connection so the persistence is
 * transactionally consistent with the rest of {@code Character.saveCharToDB}.
 */
public final class CharacterHintState {
    private static final Logger log = LoggerFactory.getLogger(CharacterHintState.class);

    private CharacterHintState() {
    }

    /**
     * Returns the set of hint ids already shown to the given character.
     * Empty set on DB failure (treat as "never seen" so the hint still fires once).
     */
    public static Set<String> loadSeen(int characterId) {
        if (characterId <= 0) {
            return Collections.emptySet();
        }
        Set<String> seen = new HashSet<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT hint_id FROM coop_character_hint_seen WHERE character_id = ?")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString(1);
                    if (id != null && !id.isBlank()) {
                        seen.add(id);
                    }
                }
            }
        } catch (SQLException e) {
            log.error("coop_character_hint_seen: load failed for character {}", characterId, e);
            return Collections.emptySet();
        }
        return seen;
    }

    /**
     * Persists a batch of "now seen" markers under the caller's transaction. The
     * caller MUST be inside a JDBC transaction ({@code autoCommit=false}); the
     * SQL is idempotent so re-application is harmless.
     *
     * @return true on success, false if any insert failed (caller should rollback).
     */
    public static boolean persistSeen(Connection con, int characterId, List<String> hintIds) {
        if (con == null || characterId <= 0 || hintIds == null || hintIds.isEmpty()) {
            return true;
        }
        List<String> cleaned = new ArrayList<>(hintIds.size());
        for (String id : hintIds) {
            if (id != null && !id.isBlank()) {
                cleaned.add(id);
            }
        }
        if (cleaned.isEmpty()) {
            return true;
        }
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT IGNORE INTO coop_character_hint_seen (character_id, hint_id) VALUES (?, ?)")) {
            for (String id : cleaned) {
                ps.setInt(1, characterId);
                ps.setString(2, id);
                ps.addBatch();
            }
            ps.executeBatch();
            return true;
        } catch (SQLException e) {
            log.error("coop_character_hint_seen: persist failed for character {} ({} hints)",
                    characterId, cleaned.size(), e);
            return false;
        }
    }
}
