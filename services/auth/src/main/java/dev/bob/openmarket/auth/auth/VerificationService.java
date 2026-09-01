package dev.bob.openmarket.auth.auth;

import dev.bob.openmarket.auth.common.NotFoundException;
import dev.bob.openmarket.auth.common.UnauthorizedException;
import dev.bob.openmarket.auth.domain.VerificationToken;
import dev.bob.openmarket.auth.repository.VerificationTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Single-use verification tokens (email verify / email change / password
 * reset). Same discipline as refresh tokens: only SHA-256 hashes stored,
 * raw value exists only in the emailed link. `consumed` = used_at set.
 */
@Service
public class VerificationService {

    public static final String TYPE_EMAIL_VERIFY = "email_verify";
    public static final String TYPE_EMAIL_CHANGE = "email_change";
    public static final String TYPE_PASSWORD_RESET = "password_reset";

    private final VerificationTokenRepository repository;
    private final SecureRandom random = new SecureRandom();

    @Value("${auth.verification.email-verify-hours:24}")
    private long emailVerifyHours;
    @Value("${auth.verification.password-reset-minutes:60}")
    private long passwordResetMinutes;

    public VerificationService(VerificationTokenRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public String issue(UUID userId, String type, String identifier) {
        String raw = newTokenValue();
        VerificationToken token = new VerificationToken();
        token.setUserId(userId);
        token.setType(type);
        token.setIdentifier(identifier == null ? null : identifier.toLowerCase());
        token.setTokenHash(hash(raw));
        token.setExpiresAt(Instant.now().plus(
            TYPE_PASSWORD_RESET.equals(type)
                ? Duration.ofMinutes(passwordResetMinutes)
                : Duration.ofHours(emailVerifyHours)));
        repository.save(token);
        return raw;
    }

    /**
     * Consumes a raw token of any of the expected types. Throws
     * `invalid_token` / `token_expired`; on success the row is marked used
     * (single-use — the mark is an atomic conditional update, so a
     * double-submission race produces exactly one winner).
     */
    @Transactional
    public VerificationToken consume(String raw, String... expectedTypes) {
        if (raw == null || raw.isBlank()) {
            throw new UnauthorizedException("invalid_token", "Token is required");
        }
        VerificationToken token = repository.findByTokenHash(hash(raw))
            .orElseThrow(() -> new UnauthorizedException("invalid_token", "Unknown or already used token"));
        if (token.getUsedAt() != null) {
            throw new UnauthorizedException("invalid_token", "Unknown or already used token");
        }
        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new UnauthorizedException("token_expired", "This token has expired");
        }
        if (expectedTypes.length > 0 && !List.of(expectedTypes).contains(token.getType())) {
            throw new UnauthorizedException("invalid_token", "Token is not valid for this action");
        }
        // The checks above are fast paths — the real single-use guarantee is
        // this atomic consume. The bulk UPDATE bypasses the persistence
        // context, so mirror usedAt onto the managed entity: the caller sees
        // the row as used, and flush rewrites the identical value instead of
        // a stale null.
        Instant now = Instant.now();
        if (repository.consume(token.getId(), now) == 0) {
            throw new UnauthorizedException("invalid_token", "Unknown or already used token");
        }
        token.setUsedAt(now);
        return token;
    }

    private String newTokenValue() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
