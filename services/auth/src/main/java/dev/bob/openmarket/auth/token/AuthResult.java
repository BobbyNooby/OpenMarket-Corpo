package dev.bob.openmarket.auth.token;

import dev.bob.openmarket.auth.domain.User;

/**
 * What an auth flow (register/login/refresh) hands back: the identity
 * plus the cookie-ready token pair.
 */
public record AuthResult(User user, String accessToken, String refreshToken) {

    public static AuthResult of(User user, TokenPair pair) {
        return new AuthResult(user, pair.accessToken(), pair.refreshToken());
    }
}
