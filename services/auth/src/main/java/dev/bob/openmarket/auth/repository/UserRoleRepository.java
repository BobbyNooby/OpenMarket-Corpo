package dev.bob.openmarket.auth.repository;

import dev.bob.openmarket.auth.domain.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface UserRoleRepository extends JpaRepository<UserRole, UUID> {

    @Query("select u.roleId from UserRole u where u.userId = :userId")
    List<String> findRoleIdsByUserId(UUID userId);

    List<UserRole> findByUserId(UUID userId);

    /** Immediate SQL delete — flush-order safe for the delete-then-insert swap. */
    @Modifying(flushAutomatically = true)
    @Query("delete from UserRole u where u.userId = :userId")
    void deleteAllForUser(UUID userId);
}
