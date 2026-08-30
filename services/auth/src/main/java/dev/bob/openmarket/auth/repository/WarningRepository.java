package dev.bob.openmarket.auth.repository;

import dev.bob.openmarket.auth.domain.Warning;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WarningRepository extends JpaRepository<Warning, UUID> {

    List<Warning> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
