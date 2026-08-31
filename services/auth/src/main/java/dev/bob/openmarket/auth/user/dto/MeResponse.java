package dev.bob.openmarket.auth.user.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The full "me" payload: identity + marketplace profile + login methods. */
public record MeResponse(
    UUID id,
    String email,
    String name,
    String avatarUrl,
    boolean emailVerified,
    List<String> roles,
    LoginMethods loginMethods,
    Profile profile
) {

    /**
     * Which ways this account can log in. The frontend uses it to decide
     * which flows to offer (e.g. "set a password" only when password=false).
     */
    public record LoginMethods(boolean password, List<String> providers) {
    }

    public record Profile(
        String username,
        String bio,
        Map<String, String> socialLinks,
        String accentColor,
        String language,
        Map<String, Boolean> notificationPreferences,
        String avatarUrl
    ) {
    }
}
