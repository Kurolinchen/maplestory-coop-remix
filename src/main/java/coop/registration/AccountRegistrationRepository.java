package coop.registration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import javax.sql.DataSource;

import tools.BCrypt;

public class AccountRegistrationRepository {
    private static final String INSERT_SQL = "INSERT INTO accounts (`name`, password) VALUES (?, ?)";

    private final DataSource dataSource;

    public AccountRegistrationRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public AccountCreationResult create(String username, String rawPassword) {
        String hash = BCrypt.hashpw(rawPassword, BCrypt.gensalt(12));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(INSERT_SQL)) {
            ps.setString(1, username);
            ps.setString(2, hash);
            ps.executeUpdate();
            return AccountCreationResult.CREATED;
        } catch (SQLException e) {
            return isDuplicate(e) ? AccountCreationResult.DUPLICATE : AccountCreationResult.UNAVAILABLE;
        }
    }

    public boolean isReachable() {
        try (Connection connection = dataSource.getConnection()) {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    private static boolean isDuplicate(SQLException e) {
        return e.getErrorCode() == 1062;
    }
}
