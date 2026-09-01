package dev.bob.openmarket.auth.user.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Partial update: null fields are left untouched. Map fields replace the
 * whole stored JSON object (send the merged map you want stored). @Size on
 * the maps caps entry count — @Size measures Map size, not serialized bytes.
 */
public record UpdateMeRequest(
    @Size(max = 80, message = "Name must be at most 80 characters")
    String name,

    @Size(min = 3, max = 32, message = "Username must be 3-32 characters")
    @Pattern(regexp = "^[a-z0-9_-]+$", message = "Username may only contain a-z, 0-9, _ and -")
    String username,

    @Size(max = 2000, message = "Bio must be at most 2000 characters")
    String bio,

    @Size(max = 16, message = "Social links must have at most 16 entries")
    Map<String, String> socialLinks,

    @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "accentColor must be a hex color like #34d399")
    String accentColor,

    @Size(max = 8, message = "Language must be at most 8 characters")
    String language,

    @Size(max = 2048, message = "Avatar URL must be at most 2048 characters")
    @Pattern(regexp = "^https://.*", message = "Avatar URL must start with https://")
    String avatarUrl,

    @Size(max = 16, message = "Notification preferences must have at most 16 entries")
    Map<String, Boolean> notificationPreferences
) {
}
