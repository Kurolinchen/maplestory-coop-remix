package coop.registration;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class RegistrationValidator {
    public static final int USERNAME_MIN = 4;
    public static final int USERNAME_MAX = 13;
    public static final int PASSWORD_MIN = 12;
    public static final int PASSWORD_MAX = 64;
    public static final int PASSWORD_MAX_BYTES = 72;

    private RegistrationValidator() {
    }

    public enum Status {
        OK,
        USERNAME_INVALID,
        PASSWORD_INVALID,
        PASSWORD_MISMATCH
    }

    public record Result(Status status, String username) {
        public boolean accepted() {
            return status == Status.OK;
        }
    }

    public static Result validate(String username, String password, String confirmation) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(confirmation, "confirmation");

        if (!username.matches("[A-Za-z0-9_]{" + USERNAME_MIN + "," + USERNAME_MAX + "}")) {
            return new Result(Status.USERNAME_INVALID, username);
        }
        if (!isAsciiPrintable(password, true) || password.length() < PASSWORD_MIN || password.length() > PASSWORD_MAX
                || password.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTES) {
            return new Result(Status.PASSWORD_INVALID, username);
        }
        if (!constantTimeEquals(password, confirmation)) {
            return new Result(Status.PASSWORD_MISMATCH, username);
        }
        return new Result(Status.OK, username);
    }

    public static byte[] sha256(String value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean isAsciiPrintable(String value, boolean allowSpace) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || c > 126) {
                return false;
            }
            if (c == ' ' && !allowSpace) {
                return false;
            }
        }
        return true;
    }

    static boolean constantTimeEquals(String a, String b) {
        byte[] left = a.getBytes(StandardCharsets.UTF_8);
        byte[] right = b.getBytes(StandardCharsets.UTF_8);
        int diff = left.length ^ right.length;
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int l = i < left.length ? left[i] : 0;
            int r = i < right.length ? right[i] : 0;
            diff |= l ^ r;
        }
        return diff == 0;
    }
}
