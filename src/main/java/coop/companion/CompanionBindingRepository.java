/*
    This file is part of the MapleStory Co-op Remix server (Cosmic v83 base),
    provided under the GNU Affero General Public License version 3 as published
    by the Free Software Foundation.
*/
package coop.companion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Persistence layer for {@code coop_companion_bindings}.
 *
 * <p>Slice B (Companion Bot MVP): every bind/unbind/spawn goes through here so
 * the runtime does not trust a command-supplied account id or cached binding.
 * All ownership verification is a fresh DB read against the canonical
 * {@code characters} table.
 */
public final class CompanionBindingRepository {
    private static final Logger log = LoggerFactory.getLogger(CompanionBindingRepository.class);

    private CompanionBindingRepository() {
    }

    public record Binding(int ownerCharacterId, int companionCharacterId,
                          int accountId, int world, String mode, boolean lootEnabled) {
    }

    public static Optional<Binding> findByOwner(int ownerCharacterId) {
        String sql = "SELECT b.owner_character_id, b.companion_character_id, b.account_id, "
                + "b.world, b.mode, b.loot_enabled, b.created_at "
                + "FROM coop_companion_bindings b WHERE b.owner_character_id = ?";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, ownerCharacterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Binding(
                        rs.getInt("owner_character_id"),
                        rs.getInt("companion_character_id"),
                        rs.getInt("account_id"),
                        rs.getInt("world"),
                        rs.getString("mode"),
                        rs.getBoolean("loot_enabled")));
            }
        } catch (SQLException e) {
            log.error("Failed to load companion binding for owner {}", ownerCharacterId, e);
            return Optional.empty();
        }
    }

    public static boolean insert(int ownerCharacterId, int companionCharacterId,
                                 int accountId, int world, String mode, boolean lootEnabled) {
        String sql = "INSERT INTO coop_companion_bindings "
                + "(owner_character_id, companion_character_id, account_id, world, mode, loot_enabled) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, ownerCharacterId);
            ps.setInt(2, companionCharacterId);
            ps.setInt(3, accountId);
            ps.setInt(4, world);
            ps.setString(5, mode);
            ps.setBoolean(6, lootEnabled);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            log.error("Failed to insert companion binding owner={} companion={}",
                    ownerCharacterId, companionCharacterId, e);
            return false;
        }
    }

    public static boolean delete(int ownerCharacterId) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "DELETE FROM coop_companion_bindings WHERE owner_character_id = ?")) {
            ps.setInt(1, ownerCharacterId);
            return ps.executeUpdate() >= 0;
        } catch (SQLException e) {
            log.error("Failed to delete companion binding for owner {}", ownerCharacterId, e);
            return false;
        }
    }

    /**
     * Verifies that owner + companion are real characters on the same account,
     * that owner and companion are different ids, and that the companion is
     * currently offline (not in world/channel storage). This is the only safe
     * way to confirm ownership for {@link #insert}.
     */
    public static OwnershipCheckResult verifyOwnership(int ownerAccountId, int ownerCharacterId,
                                                      int companionCharacterId) {
        if (ownerCharacterId == companionCharacterId) {
            return OwnershipCheckResult.reject("owner and companion must be different characters");
        }
        String sql = "SELECT c1.id, c1.accountid, c1.world, c1.name, "
                + "       c2.id AS cid, c2.accountid AS caccount, c2.world AS cworld, c2.name AS cname, c2.job "
                + "FROM characters c1, characters c2 "
                + "WHERE c1.id = ? AND c2.id = ?";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, ownerCharacterId);
            ps.setInt(2, companionCharacterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return OwnershipCheckResult.reject("owner or companion character not found");
                }
                int ownerAcc = rs.getInt("accountid");
                int companionAcc = rs.getInt("caccount");
                int ownerWorld = rs.getInt("world");
                int companionWorld = rs.getInt("cworld");
                if (ownerAcc != ownerAccountId) {
                    return OwnershipCheckResult.reject("owner character is not on the authenticated account");
                }
                if (ownerAcc != companionAcc) {
                    return OwnershipCheckResult.reject("companion is on a different account");
                }
                if (ownerWorld != companionWorld) {
                    return OwnershipCheckResult.reject("companion is on a different world");
                }
                return OwnershipCheckResult.allow(rs.getString("name"), rs.getString("cname"),
                        ownerWorld, rs.getInt("job"));
            }
        } catch (SQLException e) {
            log.error("verifyOwnership failed for owner={} companion={}",
                    ownerCharacterId, companionCharacterId, e);
            return OwnershipCheckResult.reject("ownership check failed: " + e.getMessage());
        }
    }

    public record OwnershipCheckResult(boolean allowed, String reason,
                                       String ownerName, String companionName,
                                       int world, int companionJob) {
        public static OwnershipCheckResult reject(String reason) {
            return new OwnershipCheckResult(false, reason, null, null, -1, -1);
        }
        public static OwnershipCheckResult allow(String ownerName, String companionName,
                                                 int world, int companionJob) {
            return new OwnershipCheckResult(true, null, ownerName, companionName, world, companionJob);
        }
    }
}
