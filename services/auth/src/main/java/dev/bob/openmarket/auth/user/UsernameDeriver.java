package dev.bob.openmarket.auth.user;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Turns a display name / email into a valid `user_profiles.username`
 * (^[a-z0-9_-]{3,32}$), uniquified with a short random suffix. Shared by
 * email registration and Discord signups so both produce the same shape.
 */
public final class UsernameDeriver {

    private static final int MAX = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private UsernameDeriver() {
    }

    public static String derive(String name, String email) {
        String base = (name != null && !name.isBlank() ? name : email.split("@")[0])
            .toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
        if (base.length() < 3) {
            base = base + "-user";
        }
        String suffix = suffix();
        if (base.length() + 1 + suffix.length() > MAX) {
            base = base.substring(0, MAX - 1 - suffix.length());
        }
        return base + "-" + suffix;
    }

    private static String suffix() {
        byte[] bytes = new byte[2];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
