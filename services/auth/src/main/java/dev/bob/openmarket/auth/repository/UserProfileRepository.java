package dev.bob.openmarket.auth.repository;

import dev.bob.openmarket.auth.domain.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {

    boolean existsByUsername(String username);
}
