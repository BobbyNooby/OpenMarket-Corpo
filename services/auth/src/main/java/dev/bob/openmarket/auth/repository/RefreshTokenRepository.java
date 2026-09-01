package dev.bob.openmarket.auth.repository;

import dev.bob.openmarket.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByUserIdAndRevokedAtIsNull(UUID userId);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    int revokeActiveInFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    /**
     * Atomic consume for rotation: revokes the row only if it is still live.
     * @return 1 if this caller won the rotation race, 0 if someone else did
     */
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.id = :id and t.revokedAt is null")
    int consume(@Param("id") UUID id, @Param("now") Instant now);

    /** "Log out everywhere" — one bulk UPDATE, beats a concurrent rotate() in both orderings. */
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    /** Single-token logout without loading the row. Losers of the race leave the row untouched. */
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.tokenHash = :tokenHash and t.revokedAt is null")
    int revokeByTokenHash(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    /** Ownership-guarded variant for the DELETE /sessions/{familyId} endpoint. */
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now "
        + "where t.familyId = :familyId and t.userId = :userId and t.revokedAt is null")
    int revokeActiveInFamilyForUser(@Param("familyId") UUID familyId,
                                    @Param("userId") UUID userId,
                                    @Param("now") Instant now);

    /** Password change: kill every device session except the one doing the change. */
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now "
        + "where t.userId = :userId and t.familyId <> :keepFamilyId and t.revokedAt is null")
    int revokeAllForUserExcept(@Param("userId") UUID userId,
                               @Param("keepFamilyId") UUID keepFamilyId,
                               @Param("now") Instant now);

    /**
     * Cleanup sweep: rows whose expiry passed more than the retention window
     * ago (cutoff computed by {@link dev.bob.openmarket.auth.token.RefreshTokenCleanupJob}).
     * Never touches revoked rows — those are deleted on their own clock below.
     */
    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);

    /** Cleanup sweep: rows revoked longer than the forensic retention window ago. */
    @Modifying
    @Query("delete from RefreshToken t where t.revokedAt is not null and t.revokedAt < :cutoff")
    int deleteRevokedBefore(@Param("cutoff") Instant cutoff);
}
