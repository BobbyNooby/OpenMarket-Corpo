package dev.bob.openmarket.auth.repository;

import dev.bob.openmarket.auth.domain.VerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface VerificationTokenRepository extends JpaRepository<VerificationToken, UUID> {

    Optional<VerificationToken> findByTokenHash(String tokenHash);

    /**
     * Atomic single-use consume: marks the row used only if it isn't yet.
     * @return 1 if this caller won, 0 if the token was consumed concurrently
     */
    @Modifying
    @Query("update VerificationToken t set t.usedAt = :now where t.id = :id and t.usedAt is null")
    int consume(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Supersede: marks every outstanding token of this user+type used, so a
     * re-issue leaves exactly one live link. Keyed on (userId, type), not the
     * identifier — for email_change the identifier is the target address, and
     * superseding "change to A" when issuing "change to B" is the point.
     * @return number of tokens superseded
     */
    @Modifying
    @Query("update VerificationToken t set t.usedAt = :now where t.userId = :userId and t.type = :type and t.usedAt is null")
    int supersedeAllForUser(@Param("userId") UUID userId, @Param("type") String type, @Param("now") Instant now);

    /**
     * Cleanup sweep ({@link dev.bob.openmarket.auth.token.RefreshTokenCleanupJob}):
     * consumed tokens past the audit retention window, plus never-consumed
     * tokens whose expiry is long past. Both states are pure waste.
     */
    @Modifying
    @Query("delete from VerificationToken t "
        + "where (t.usedAt is not null and t.usedAt < :usedCutoff) "
        + "or (t.usedAt is null and t.expiresAt < :expiredCutoff)")
    int deleteStaleBefore(@Param("usedCutoff") Instant usedCutoff, @Param("expiredCutoff") Instant expiredCutoff);
}
