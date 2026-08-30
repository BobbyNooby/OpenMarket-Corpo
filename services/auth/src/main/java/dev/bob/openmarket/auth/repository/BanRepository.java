package dev.bob.openmarket.auth.repository;

import dev.bob.openmarket.auth.domain.Ban;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BanRepository extends JpaRepository<Ban, UUID> {

    List<Ban> findByUserIdOrderByBannedAtDesc(UUID userId);

    Optional<Ban> findFirstByUserIdAndLiftedAtIsNullOrderByBannedAtDesc(UUID userId);
}
