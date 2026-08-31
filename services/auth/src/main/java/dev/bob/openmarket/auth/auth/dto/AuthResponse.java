package dev.bob.openmarket.auth.auth.dto;

import dev.bob.openmarket.auth.user.dto.UserResponse;

/**
 * Successful auth responses carry the user; the tokens themselves are
 * set as httpOnly cookies (om_access / om_refresh), not in the body.
 */
public record AuthResponse(UserResponse user) {
}
