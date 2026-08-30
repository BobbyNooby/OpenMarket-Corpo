package dev.bob.openmarket.auth.repository;

import dev.bob.openmarket.auth.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Email is always stored lowercase; exact match is enough. */
    Optional<User> findByEmail(String email);

    Optional<User> findByIdAndDeletedAtIsNull(UUID id);

    boolean existsByEmail(String email);

    Page<User> findAllByDeletedAtIsNull(Pageable pageable);

    @Query("select u from User u where u.deletedAt is null "
        + "and (lower(u.email) like %:query% or lower(u.name) like %:query%)")
    Page<User> search(String query, Pageable pageable);
}
