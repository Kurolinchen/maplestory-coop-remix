package coop.security;

public final class GmLevel {
    public static final int MIN = 0;
    public static final int MAX = 6;

    private GmLevel() {
    }

    public static int normalize(int level) {
        return Math.max(MIN, Math.min(level, MAX));
    }

    public static boolean isValid(int level) {
        return level >= MIN && level <= MAX;
    }
}
