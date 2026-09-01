package dev.bob.openmarket.auth.repository;

import dev.bob.openmarket.auth.domain.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /** Live owners other than :userId — backs the last-owner guard in setRoles. */
    @Query("select count(r) from UserRole r where r.roleId = 'owner' and r.userId <> :userId "
        + "and exists (select 1 from User u where u.id = r.userId and u.deletedAt is null)")
    long countLiveOwnersExcluding(UUID userId);

    /** Live owners across the platform — backs the first-account owner bootstrap in register. */
    @Query("select count(r) from UserRole r where r.roleId = 'owner' "
        + "and exists (select 1 from User u where u.id = r.userId and u.deletedAt is null)")
    long countLiveOwners();
}
