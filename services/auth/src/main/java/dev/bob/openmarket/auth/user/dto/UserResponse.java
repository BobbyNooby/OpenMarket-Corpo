package dev.bob.openmarket.auth.user.dto;

import dev.bob.openmarket.auth.domain.User;

import java.util.List;
import java.util.UUID;

/** Public view of a user: what other services and the frontend get to see. */
public record UserResponse(
    UUID id,
    String email,
    String name,
    String avatarUrl,
    boolean emailVerified,
    List<String> roles
) {

    public static UserResponse from(User user, List<String> roles) {
        return new UserResponse(
            user.getId(),
            user.getEmail(),
            user.getName(),
            user.getAvatarUrl(),
            user.isEmailVerified(),
            roles);
    }
}
