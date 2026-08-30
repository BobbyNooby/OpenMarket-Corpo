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
}
