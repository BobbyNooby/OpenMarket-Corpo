package dev.bob.openmarket.auth.token;

/** Issued token pair, ready to be written as cookies. */
public record TokenPair(String accessToken, String refreshToken) {
}
