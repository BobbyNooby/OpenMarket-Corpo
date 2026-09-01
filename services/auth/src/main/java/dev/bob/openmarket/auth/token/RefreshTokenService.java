package dev.bob.openmarket.auth.token;

import dev.bob.openmarket.auth.common.UnauthorizedException;
import dev.bob.openmarket.auth.config.JwtProperties;
import dev.bob.openmarket.auth.domain.RefreshToken;
import dev.bob.openmarket.auth.repository.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Refresh token lifecycle: issue → rotate on every use → revoke on logout.
 *
 * <p>Only the SHA-256 hash of a token is stored, so a DB leak can't be replayed
 * as sessions. Rotation works like this:
 * <ul>
 *   <li>every refresh call consumes the presented token (revoked_at = now)
 *       and issues a new one in the same {@code family_id}</li>
 *   <li>the consume is an atomic conditional UPDATE ({@code revoked_at is null}
 *       predicate, decision on the row count) — concurrent refreshes can't both
 *       succeed and fork the family; the loser is treated exactly like a replay</li>
 *   <li>presenting an already-consumed token is treated as theft evidence:
 *       the whole family is revoked and the client must log in again</li>
 *   <li>the successor inherits the family's original expiry — a family lives
 *       7 days from login no matter how often it rotates (absolute, not sliding)</li>
 * </ul>
 */
@Service
public class RefreshTokenService {

    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final JwtProperties props;
    private final SecureRandom random = new SecureRandom();

    /**
     * Independent transaction for theft response: the family revocation must
     * commit even though rotate() then throws and rolls back its own tx.
     * (An @Transactional(REQUIRES_NEW) method would NOT work here — rotate
     * calls it on `this`, bypassing the Spring proxy.)
     */
    private final TransactionTemplate familyRevokeTx;

    public RefreshTokenService(RefreshTokenRepository repository,
                               JwtProperties props,
                               PlatformTransactionManager txManager) {
        this.repository = repository;
        this.props = props;
        this.familyRevokeTx = new TransactionTemplate(txManager);
        this.familyRevokeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Creates the first token of a new family (login / register). Returns the raw value. */
    @Transactional
    public String issue(UUID userId, String userAgent, String ip) {
        Instant expiresAt = Instant.now().plusSeconds(props.getRefreshTtlDays() * 24 * 60 * 60);
        return createAndSave(userId, UUID.randomUUID(), null, userAgent, ip, expiresAt).rawToken();
    }

    /**
     * Consumes {@code rawToken} and returns the successor token — same family,
     * inheriting its absolute expiry — plus the stored entity. Throws if the
     * token is unknown, expired, or already consumed. The consume itself is an
     * atomic conditional UPDATE, so of two concurrent refreshes exactly one
     * proceeds; the loser takes the reuse (theft) path.
     */
    @Transactional
    public Rotated rotate(String rawToken) {
        RefreshToken token = find(rawToken);

        if (token.getRevokedAt() != null) {
            // reuse of a consumed token → assume theft, kill the family.
            throwReuse(token.getFamilyId());
        }
        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new UnauthorizedException("refresh_token_expired", "Refresh token expired");
        }

        // The in-memory revoked_at check above is only a fast path — the real
        // decision is this consume: `update … set revoked_at = now where
        // id = ? and revoked_at is null`, judged by row count. Two concurrent
        // refreshes can't both win.
        Instant now = Instant.now();
        if (repository.consume(token.getId(), now) == 0) {
            throwReuse(token.getFamilyId());
        }
        // The bulk UPDATE bypassed the persistence context, so the managed
        // entity still believes revoked_at is null. Mirror the consumed value
        // onto it: flush then rewrites the identical timestamp instead of ever
        // resurrecting the row with a stale null.
        token.setRevokedAt(now);

        Issued issued = createAndSave(token.getUserId(), token.getFamilyId(), token.getId(),
            token.getUserAgent(), token.getIpAddress(), token.getExpiresAt());
        return new Rotated(issued.entity(), issued.rawToken());
    }

    /** Theft response: revoke the family in an independent tx, then 401. */
    private void throwReuse(UUID familyId) {
        // Runs in its own tx and commits before the 401 goes out.
        familyRevokeTx.executeWithoutResult(status ->
            repository.revokeActiveInFamily(familyId, Instant.now()));
        throw new UnauthorizedException("refresh_token_reused",
            "Refresh token was already used; all sessions were revoked");
    }

    /** Logout of the session this token belongs to. No-op if the token is unknown. */
    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new UnauthorizedException("missing_refresh_token", "Refresh token is required");
        }
        // Conditional update, not load-then-set: can't race a concurrent
        // rotation, and an already-consumed token keeps its original
        // consumption timestamp.
        repository.revokeByTokenHash(hash(rawToken), Instant.now());
    }

    /** E.g. on account deletion / "log out everywhere". */
    @Transactional
    public void revokeAllForUser(UUID userId) {
        // One bulk UPDATE — beats a concurrent rotate() in both orderings.
        repository.revokeAllForUser(userId, Instant.now());
    }

    /** Password change: revoke all except the current session's family. */
    @Transactional
    public void revokeAllForUserExcept(UUID userId, UUID keepFamilyId) {
        if (keepFamilyId == null) {
            revokeAllForUser(userId);
            return;
        }
        repository.revokeAllForUserExcept(userId, keepFamilyId, Instant.now());
    }

    /** Revokes one device's session family, owned by {@code userId}. @return false if unknown. */
    @Transactional
    public boolean revokeFamilyForUser(UUID userId, UUID familyId) {
        return repository.revokeActiveInFamilyForUser(familyId, userId, Instant.now()) > 0;
    }

    /** Family id of a raw refresh token, or null if unknown/absent. Never throws. */
    @Transactional(readOnly = true)
    public UUID familyOf(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        return repository.findByTokenHash(hash(rawToken)).map(RefreshToken::getFamilyId).orElse(null);
    }

    /**
     * Live session families for the "manage devices" UI. One row per family —
     * the newest token of the family provides device metadata.
     */
    @Transactional(readOnly = true)
    public java.util.List<SessionView> listSessions(UUID userId, UUID currentFamilyId) {
        Instant now = Instant.now();
        var byFamily = repository.findByUserIdAndRevokedAtIsNull(userId).stream()
            .filter(t -> t.getExpiresAt().isAfter(now))
            .collect(java.util.stream.Collectors.groupingBy(RefreshToken::getFamilyId));

        return byFamily.entrySet().stream()
            .map(e -> {
                RefreshToken newest = e.getValue().stream()
                    .max(java.util.Comparator.comparing(RefreshToken::getCreatedAt)).orElseThrow();
                return new SessionView(
                    e.getKey(),
                    newest.getUserAgent(),
                    newest.getIpAddress(),
                    e.getValue().stream().map(RefreshToken::getCreatedAt).min(java.util.Comparator.naturalOrder()).orElseThrow(),
                    e.getValue().stream().map(RefreshToken::getExpiresAt).max(java.util.Comparator.naturalOrder()).orElseThrow(),
                    e.getKey().equals(currentFamilyId));
            })
            .sorted(java.util.Comparator.comparing(SessionView::createdAt).reversed())
            .toList();
    }

    /** One row of the sessions list. */
    public record SessionView(UUID familyId, String userAgent, String ipAddress,
                              Instant createdAt, Instant expiresAt, boolean current) {
    }

    private RefreshToken find(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new UnauthorizedException("missing_refresh_token", "Refresh token is required");
        }
        return repository.findByTokenHash(hash(rawToken))
            .orElseThrow(() -> new UnauthorizedException("invalid_refresh_token", "Unknown refresh token"));
    }

    private Issued createAndSave(UUID userId, UUID familyId, UUID rotatedFrom,
                                 String userAgent, String ip, Instant expiresAt) {
        String raw = newTokenValue();

        RefreshToken token = new RefreshToken();
        token.setUserId(userId);
        token.setFamilyId(familyId);
        token.setRotatedFromId(rotatedFrom);
        token.setTokenHash(hash(raw));
        token.setUserAgent(userAgent);
        token.setIpAddress(ip);
        token.setExpiresAt(expiresAt);
        RefreshToken saved = repository.save(token);

        return new Issued(saved, raw);
    }

    private String newTokenValue() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** The saved row plus the raw token value (which is never stored). */
    private record Issued(RefreshToken entity, String rawToken) {
    }

    /** Result of a rotation: the new row and the raw successor token to hand out. */
    public record Rotated(RefreshToken entity, String rawToken) {
    }
}
