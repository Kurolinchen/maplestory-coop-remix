package coop.registration;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

public record RegistrationConfig(String publicOrigin,
                                String jdbcUrl,
                                String dbUser,
                                String dbPassword,
                                String invitePassphrase,
                                int port,
                                int perIpBurst,
                                long windowNanos,
                                int globalHourlyCap,
                                long sessionTtlNanos,
                                String resourceDir) {

    private static final byte[] INVITE_SALT = "coop-registration-invite".getBytes(StandardCharsets.UTF_8);

    public RegistrationConfig {
        Objects.requireNonNull(publicOrigin, "publicOrigin");
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Objects.requireNonNull(dbUser, "dbUser");
        Objects.requireNonNull(dbPassword, "dbPassword");
        Objects.requireNonNull(invitePassphrase, "invitePassphrase");
        Objects.requireNonNull(resourceDir, "resourceDir");
        URI origin = URI.create(publicOrigin);
        if (!"https".equalsIgnoreCase(origin.getScheme()) || origin.getHost() == null
                || origin.getUserInfo() != null || origin.getQuery() != null || origin.getFragment() != null
                || !origin.getPath().isEmpty()) {
            throw new IllegalArgumentException("publicOrigin must be an HTTPS origin without a path");
        }
    }

    public byte[] inviteDigest() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(INVITE_SALT);
            return digest.digest(invitePassphrase.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public byte[] saltedDigest(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(INVITE_SALT);
            return digest.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public boolean inviteMatches(String candidate) {
        return candidate != null && MessageDigest.isEqual(inviteDigest(), saltedDigest(candidate));
    }
}
