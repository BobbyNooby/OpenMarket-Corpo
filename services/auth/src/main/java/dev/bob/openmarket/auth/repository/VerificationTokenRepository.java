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
     * Cleanup sweep ({@link dev.bob.openmarket.auth.token.RefreshTokenCleanupJob}):
     * consumed tokens past the audit retention window, plus never-consumed
     * tokens whose expiry is long past. Both states are pure waste.
     */
}
