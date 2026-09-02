package coop.security;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

public final class GmLevelPersistence {
    private static final Logger log = LoggerFactory.getLogger(GmLevelPersistence.class);

    private GmLevelPersistence() {
    }

    public static boolean updateCharacter(int characterId, int gmLevel) {
        if (!GmLevel.isValid(gmLevel)) {
            throw new IllegalArgumentException("GM level must be from 0 to 6");
        }
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement ps = connection.prepareStatement("UPDATE characters SET gm = ? WHERE id = ?")) {
            ps.setInt(1, gmLevel);
            ps.setInt(2, characterId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            log.error("Could not persist GM level {} for character {}", gmLevel, characterId, e);
            return false;
        }
    }
}
