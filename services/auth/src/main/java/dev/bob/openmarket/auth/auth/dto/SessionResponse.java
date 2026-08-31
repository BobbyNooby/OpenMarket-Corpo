package dev.bob.openmarket.auth.auth.dto;

import java.time.Instant;
import java.util.UUID;

/** One live session family in the "manage devices" list. */
public record SessionResponse(
    UUID familyId,
    String userAgent,
    String ipAddress,
    Instant createdAt,
    Instant expiresAt,
    boolean current
) {
}
